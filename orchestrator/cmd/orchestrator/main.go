// Orchestrator v1.2 entry point (DEC-014 + DEC-013).
// Запускает HTTP-сервер на 0.0.0.0:8769 + cron-scheduler.
// v1.2 добавляет: state-service integration, incremental workflow, lock-protection.
package main

import (
	"context"
	"encoding/json"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/ciriycpro/orchestrator/activities"
	"github.com/ciriycpro/orchestrator/auth"
	"github.com/ciriycpro/orchestrator/config"
	"github.com/ciriycpro/orchestrator/logging"
	"github.com/ciriycpro/orchestrator/ratelimit"
	"github.com/ciriycpro/orchestrator/server"
	"github.com/ciriycpro/orchestrator/workflow"

	"github.com/robfig/cron/v3"
)

func main() {
	logger := logging.Init()

	cfg, err := config.Load()
	if err != nil {
		logger.Error("config.load.fail", slog.String("error", err.Error()))
		os.Exit(1)
	}
	logger.Info("config.loaded",
		slog.String("http_addr", cfg.HTTPAddr()),
		slog.String("schedule", cfg.Schedule),
		slog.Int("service_timeout_sec", cfg.ServiceTimeoutSec),
		slog.Int("lock_ttl_sec", cfg.WorkflowLockTTLSeconds),
		slog.Int("fallback_period_hours", cfg.FallbackPeriodHours),
	)

	// Создаём activities
	mailAct := activities.NewMailActivity(cfg.MailServiceURL, cfg.ServiceTimeout())
	attachmentAct := activities.NewAttachmentActivity(cfg.AttachmentServiceURL, cfg.ServiceTimeout())
	parserAct := activities.NewParserActivity(cfg.ParserServiceURL, cfg.ServiceTimeout())
	summaryAct := activities.NewSummaryActivity(cfg.SummaryServiceURL, cfg.ServiceTimeout())
	telegramAct := activities.NewTelegramActivity(cfg.AgentCallerURL, 10*time.Second)
	waAct := activities.NewWhatsAppActivity(cfg.AgentCallerURL, 150*time.Second)
	stateAct := activities.NewStateActivity(cfg.StateServiceURL, cfg.StateServiceAPIKey, 10*time.Second)
	notifyAct := activities.NewNotifyActivity(cfg.AgentCallerURL, 5*time.Second)

	// Compliance-logic ingest activity (DEC-0027). Создаётся только если задан API_KEY.
	var ingestAct *activities.IngestActivity
	if cfg.ComplianceLogicAPIKey != "" {
		a, e := activities.NewIngestActivity(cfg.ComplianceLogicURL, cfg.ComplianceLogicAPIKey, cfg.ComplianceLogicCACert, cfg.ServiceTimeout())
		if e != nil {
			logger.Error("startup.ingest_init_fail", slog.String("error", e.Error()))
			os.Exit(1)
		}
		ingestAct = a
	}

	// Startup readiness check
	logger.Info("startup.readiness_check")
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := mailAct.HealthCheck(ctx); err != nil {
		logger.Warn("startup.mail_service_not_ready", slog.String("error", err.Error()))
	}
	if err := attachmentAct.HealthCheck(ctx); err != nil {
		logger.Warn("startup.attachment_service_not_ready", slog.String("error", err.Error()))
	}
	if err := parserAct.HealthCheck(ctx); err != nil {
		logger.Warn("startup.parser_service_not_ready", slog.String("error", err.Error()))
	}
	if err := summaryAct.HealthCheck(ctx); err != nil {
		logger.Warn("startup.summary_service_not_ready", slog.String("error", err.Error()))
	}
	if err := telegramAct.HealthCheck(ctx); err != nil {
		logger.Warn("startup.agent_caller_not_ready", slog.String("error", err.Error()))
	}
	if err := stateAct.HealthCheck(ctx); err != nil {
		logger.Warn("startup.state_service_not_ready", slog.String("error", err.Error()))
	}
	if ingestAct != nil {
		if err := ingestAct.HealthCheck(ctx); err != nil {
			logger.Warn("startup.compliance_logic_not_ready", slog.String("error", err.Error()))
		}
	}

	// Workflow
	wf := &workflow.EmailDigestWorkflow{
		Mail:                mailAct,
		Attachment:          attachmentAct,
		Parser:              parserAct,
		Summary:             summaryAct,
		Telegram:            telegramAct,
		WhatsApp:            waAct,
		State:               stateAct,
		Notify:              notifyAct,
		Logger:              logger,
		TelegramChatID:      cfg.TelegramChatID,
		WhatsAppNumber:      cfg.WhatsAppNumber,
		LockTTLSeconds:      cfg.WorkflowLockTTLSeconds,
		FallbackPeriodHours: cfg.FallbackPeriodHours,
	}

	// Statement-vacuum workflow (DEC-0027). Создаётся только если ingestAct активен.
	var vacuumWf *workflow.StatementVacuumWorkflow
	if ingestAct != nil {
		vacuumWf = &workflow.StatementVacuumWorkflow{
			Mail:                mailAct,
			Attachment:          attachmentAct,
			Parser:              parserAct,
			Ingest:              ingestAct,
			WhatsApp:            waAct,
			Logger:              logger,
			MailboxLabel:        "compliance-5458508",
			WhatsAppNumber:      cfg.WhatsAppNumber,
			FallbackPeriodHours: cfg.FallbackPeriodHours,
		}
	}

	// HTTP-server
	srv := &server.Server{
		Cfg:      cfg,
		Logger:   logger,
		Workflow: wf,
		State:    stateAct,
	}

	metrics := &server.SimpleMetrics{}

	mux := http.NewServeMux()
	// Public (без auth)
	mux.HandleFunc("/health", srv.HandleHealth)
	mux.HandleFunc("/metrics", srv.HandleMetrics(metrics))

	// Protected (auth + rate limit)
	digestLimiter := ratelimit.NewLimiter(cfg.RateLimitDigestNow)
	authMW := auth.APIKeyMiddleware(cfg.APIKey)
	mux.Handle("/digest-now", authMW(digestLimiter.Middleware(http.HandlerFunc(srv.HandleDigestNow))))

	checkLimiter := ratelimit.NewLimiter(cfg.RateLimitCheckMail)
	mux.Handle("/check-mail", authMW(checkLimiter.Middleware(http.HandlerFunc(srv.HandleCheckMail))))

	// /state/activate — для Agent Caller при установке кнопки новому пользователю
	stateActivateLimiter := ratelimit.NewLimiter(60)
	mux.Handle("/state/activate", authMW(stateActivateLimiter.Middleware(http.HandlerFunc(srv.HandleStateActivate))))

	// Statement-vacuum manual trigger (DEC-0027). 503 если ingest disabled.
	vacuumLimiter := ratelimit.NewLimiter(10)
	mux.Handle("/statement-vacuum-now", authMW(vacuumLimiter.Middleware(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if vacuumWf == nil {
			http.Error(w, "statement-vacuum disabled (COMPLIANCE_LOGIC_API_KEY not set)", http.StatusServiceUnavailable)
			return
		}
		traceID := logging.NewTraceID()
		l := logging.WithTrace(logger, traceID)
		l.Info("vacuum.webhook.trigger")
		runCtx, runCancel := context.WithTimeout(context.Background(), 10*time.Minute)
		defer runCancel()
		result, err := vacuumWf.Run(runCtx, workflow.VacuumParams{TraceID: traceID, Trigger: "webhook"})
		if err != nil {
			l.Error("vacuum.webhook.fail", slog.String("error", err.Error()))
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		l.Info("vacuum.webhook.done",
			slog.Int("ingested", result.StatementsIngested),
			slog.Int("skipped", result.StatementsSkipped),
			slog.Bool("wa_sent", result.WhatsAppSent))
		w.WriteHeader(http.StatusOK)
		w.Write([]byte("OK\n"))
	}))))

	httpSrv := &http.Server{
		Addr:         cfg.HTTPAddr(),
		Handler:      mux,
		ReadTimeout:  10 * time.Second,
		WriteTimeout: 30 * time.Second,
		IdleTimeout:  60 * time.Second,
	}

	// Cron scheduler (если schedule не пустой)
	var c *cron.Cron
	if cfg.Schedule != "" {
		c = cron.New(cron.WithLogger(cronLogger{logger}))
		_, err := c.AddFunc(cfg.Schedule, func() {
			traceID := logging.NewTraceID()
			l := logging.WithTrace(logger, traceID)
			l.Info("cron.trigger")

			runCtx, runCancel := context.WithTimeout(context.Background(), 10*time.Minute)
			defer runCancel()

			result, err := wf.Run(runCtx, workflow.RunParams{
				TraceID: traceID,
				Trigger: workflow.TriggerCron,
				// ChatID пусто = используется TelegramChatID из config
			})
			if err != nil {
				l.Error("cron.workflow.fail", slog.String("error", err.Error()))
				return
			}
			if result.SkippedDueLock {
				l.Info("cron.workflow.skipped_due_lock")
				return
			}
			l.Info("cron.workflow.done")
		})
		if err != nil {
			logger.Error("cron.schedule.fail", slog.String("error", err.Error()))
			os.Exit(1)
		}
		c.Start()
		logger.Info("cron.started", slog.String("schedule", cfg.Schedule))
	} else {
		logger.Info("cron.disabled")
	}

	// Statement-vacuum cron (DEC-0027). Отдельное расписание от digest, UTC.
	var c2 *cron.Cron
	if cfg.StatementVacuumSchedule != "" && vacuumWf != nil {
		c2 = cron.New(cron.WithLogger(cronLogger{logger}))
		_, err := c2.AddFunc(cfg.StatementVacuumSchedule, func() {
			traceID := logging.NewTraceID()
			l := logging.WithTrace(logger, traceID)
			l.Info("vacuum.cron.trigger")
			runCtx, runCancel := context.WithTimeout(context.Background(), 10*time.Minute)
			defer runCancel()
			result, err := vacuumWf.Run(runCtx, workflow.VacuumParams{TraceID: traceID, Trigger: workflow.TriggerCron})
			if err != nil {
				l.Error("vacuum.cron.fail", slog.String("error", err.Error()))
				return
			}
			l.Info("vacuum.cron.done",
				slog.Int("ingested", result.StatementsIngested),
				slog.Int("skipped", result.StatementsSkipped),
				slog.Bool("wa_sent", result.WhatsAppSent))
		})
		if err != nil {
			logger.Error("vacuum.cron.schedule.fail", slog.String("error", err.Error()))
			os.Exit(1)
		}
		c2.Start()
		logger.Info("vacuum.cron.started", slog.String("schedule", cfg.StatementVacuumSchedule))
	} else {
		logger.Info("vacuum.cron.disabled")
	}

	// HTTP server in goroutine
	go func() {
		logger.Info("http.starting", slog.String("addr", cfg.HTTPAddr()))
		if err := httpSrv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			logger.Error("http.fail", slog.String("error", err.Error()))
			os.Exit(1)
		}
	}()

	// Graceful shutdown
	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGTERM, syscall.SIGINT)
	<-quit
	logger.Info("shutdown.start")

	if c != nil {
		stopCtx := c.Stop()
		<-stopCtx.Done()
		logger.Info("cron.stopped")
	}

	shutdownCtx, cancelShutdown := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancelShutdown()
	if err := httpSrv.Shutdown(shutdownCtx); err != nil {
		logger.Error("http.shutdown.fail", slog.String("error", err.Error()))
	}
	logger.Info("shutdown.complete")
}

// cronLogger — адаптер для robfig/cron логирования через slog.
type cronLogger struct {
	logger *slog.Logger
}

func (c cronLogger) Info(msg string, keysAndValues ...interface{}) {
	c.logger.Info("cron."+msg, slog.Any("kv", keysAndValues))
}

func (c cronLogger) Error(err error, msg string, keysAndValues ...interface{}) {
	c.logger.Error("cron."+msg, slog.String("error", err.Error()), slog.Any("kv", keysAndValues))
}

// Используется только для предотвращения unused import при компиляции
var _ = json.RawMessage{}
