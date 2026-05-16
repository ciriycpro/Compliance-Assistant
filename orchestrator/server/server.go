// Package server — HTTP-handlers orchestrator'a.
package server

import (
	"context"
	"encoding/json"
	"log/slog"
	"net/http"
	"strconv"
	"time"

	"github.com/ciriycpro/orchestrator/activities"
	"github.com/ciriycpro/orchestrator/config"
	"github.com/ciriycpro/orchestrator/logging"
	"github.com/ciriycpro/orchestrator/workflow"
)

// Server — HTTP-сервер orchestrator'a.
type Server struct {
	Cfg      *config.Config
	Logger   *slog.Logger
	Workflow *workflow.EmailDigestWorkflow
	State    *activities.StateActivity
}

// HealthResponse — для GET /health (без auth).
type HealthResponse struct {
	Status           string `json:"status"`
	Service          string `json:"service"`
	OrchestratorPort int    `json:"orchestrator_port"`
	Schedule         string `json:"schedule"`
	MailServiceURL   string `json:"mail_service_url"`
	AttachmentURL    string `json:"attachment_service_url"`
	ParserURL        string `json:"parser_service_url"`
	SummaryURL       string `json:"summary_service_url"`
	StateURL         string `json:"state_service_url"`
}

// HandleHealth — endpoint без auth (для systemd/k8s liveness).
func (s *Server) HandleHealth(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(HealthResponse{
		Status:           "ok",
		Service:          "orchestrator-v1.2",
		OrchestratorPort: s.Cfg.HTTPPort,
		Schedule:         s.Cfg.Schedule,
		MailServiceURL:   s.Cfg.MailServiceURL,
		AttachmentURL:    s.Cfg.AttachmentServiceURL,
		ParserURL:        s.Cfg.ParserServiceURL,
		SummaryURL:       s.Cfg.SummaryServiceURL,
		StateURL:         s.Cfg.StateServiceURL,
	})
}

// DigestNowRequest — body для POST /digest-now.
type DigestNowRequest struct {
	// Опционально: куда доставлять (если пусто — TelegramChatID из config).
	ChatID string `json:"chat_id,omitempty"`

	// Опционально: источник запуска. "cron" | "button". По умолчанию "button".
	Trigger string `json:"trigger,omitempty"`

	// ForceRefresh — игнорировать lock (для отладки).
	ForceRefresh bool `json:"force_refresh,omitempty"`

	// DEPRECATED v1.2: period_hours игнорируется. Период вычисляется автоматически
	// (since = last_at из state-service, until = now()).
	// Для fallback используется FALLBACK_PERIOD_HOURS из config.
	PeriodHours int `json:"period_hours,omitempty"`
}

// DigestNowResponse — ответ.
type DigestNowResponse struct {
	TraceID   string `json:"trace_id"`
	StartedAt string `json:"started_at"`
	Status    string `json:"status"`
	Message   string `json:"message,omitempty"`
}

// HandleDigestNow — POST /digest-now (требует auth + rate limit).
// Запускает workflow асинхронно. Lock защита от двойного клика реализована в workflow.
func (s *Server) HandleDigestNow(w http.ResponseWriter, r *http.Request) {
	traceID := logging.NewTraceID()
	logger := logging.WithTrace(s.Logger, traceID)

	w.Header().Set("Content-Type", "application/json")

	var req DigestNowRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil && err.Error() != "EOF" {
		w.WriteHeader(http.StatusBadRequest)
		json.NewEncoder(w).Encode(map[string]string{"error": err.Error()})
		return
	}

	// Дефолтный trigger = button
	trigger := workflow.TriggerButton
	if req.Trigger == "cron" {
		trigger = workflow.TriggerCron
	}

	logger.Info("digest_now.received",
		slog.String("chat_id", req.ChatID),
		slog.String("trigger", string(trigger)),
		slog.Bool("force_refresh", req.ForceRefresh),
	)

	if req.PeriodHours > 0 {
		logger.Warn("digest_now.deprecated_period_hours",
			slog.Int("ignored_value", req.PeriodHours),
			slog.String("hint", "period now derived from state-service last_at"),
		)
	}

	// Запускаем workflow в горутине (async)
	startedAt := time.Now()
	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Minute)
		defer cancel()

		result, err := s.Workflow.Run(ctx, workflow.RunParams{
			TraceID:      traceID,
			Trigger:      trigger,
			ChatID:       req.ChatID,
			ForceRefresh: req.ForceRefresh,
		})
		if err != nil {
			logger.Error("workflow.fail", slog.String("error", err.Error()))
			return
		}
		if result.SkippedDueLock {
			logger.Info("workflow.skipped_due_lock")
		}
	}()

	w.WriteHeader(http.StatusAccepted)
	json.NewEncoder(w).Encode(DigestNowResponse{
		TraceID:   traceID,
		StartedAt: startedAt.Format(time.RFC3339),
		Status:    "accepted",
		Message:   "workflow запущен в background",
	})
}

// HandleCheckMail — заглушка для DEC-013 Mail Check On-Demand.
func (s *Server) HandleCheckMail(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusNotImplemented)
	json.NewEncoder(w).Encode(map[string]string{
		"error": "not implemented in v1, planned for v2 (DEC-013)",
	})
}

// StateActivateRequest — body для POST /state/activate.
// Используется Agent Caller при установке кнопки новому пользователю.
type StateActivateRequest struct {
	ChatID string `json:"chat_id"`
}

// StateActivateResponse — ответ.
type StateActivateResponse struct {
	ChatID      int64  `json:"chat_id"`
	ActivatedAt string `json:"activated_at"`
	Status      string `json:"status"`
}

// HandleStateActivate — POST /state/activate.
// Записывает last_at = now() для chat_id. Используется когда новому пользователю
// устанавливается кнопка (через Agent Caller /tg/setup-button).
// С этого момента первый клик кнопки даст инкремент от момента активации, не fallback 24h.
func (s *Server) HandleStateActivate(w http.ResponseWriter, r *http.Request) {
	traceID := logging.NewTraceID()
	logger := logging.WithTrace(s.Logger, traceID)

	w.Header().Set("Content-Type", "application/json")

	var req StateActivateRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		w.WriteHeader(http.StatusBadRequest)
		json.NewEncoder(w).Encode(map[string]string{"error": err.Error()})
		return
	}
	if req.ChatID == "" {
		w.WriteHeader(http.StatusBadRequest)
		json.NewEncoder(w).Encode(map[string]string{"error": "chat_id required"})
		return
	}

	chatID, err := activities.ParseChatID(req.ChatID)
	if err != nil {
		w.WriteHeader(http.StatusBadRequest)
		json.NewEncoder(w).Encode(map[string]string{"error": err.Error()})
		return
	}

	now := time.Now().UTC()
	ctx, cancel := context.WithTimeout(r.Context(), 10*time.Second)
	defer cancel()

	err = s.State.SetLastAt(ctx, activities.SetLastAtParams{
		ChatID:    chatID,
		Timestamp: now,
	}, activities.CallOptions{TraceID: traceID})
	if err != nil {
		logger.Error("state.activate.fail", slog.Int64("chat_id", chatID), slog.String("error", err.Error()))
		w.WriteHeader(http.StatusInternalServerError)
		json.NewEncoder(w).Encode(map[string]string{"error": err.Error()})
		return
	}

	logger.Info("state.activate.ok", slog.Int64("chat_id", chatID), slog.String("activated_at", now.Format(time.RFC3339)))
	w.WriteHeader(http.StatusOK)
	json.NewEncoder(w).Encode(StateActivateResponse{
		ChatID:      chatID,
		ActivatedAt: now.Format(time.RFC3339),
		Status:      "activated",
	})
}

// SimpleMetrics — минимальные Prometheus-метрики (без зависимости на prometheus client).
// На v2 — заменится полноценной integration с prometheus/client_golang.
type SimpleMetrics struct {
	WorkflowsStarted   int64
	WorkflowsSucceeded int64
	WorkflowsFailed    int64
	WorkflowsSkipped   int64 // новое: counter для skipped из-за lock
}

// HandleMetrics — текстовый формат Prometheus.
func (s *Server) HandleMetrics(metrics *SimpleMetrics) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/plain; version=0.0.4")
		w.Write([]byte("# HELP orchestrator_workflows_started_total Total workflows started\n"))
		w.Write([]byte("# TYPE orchestrator_workflows_started_total counter\n"))
		w.Write([]byte("orchestrator_workflows_started_total " +
			strconv.FormatInt(metrics.WorkflowsStarted, 10) + "\n"))
		w.Write([]byte("# HELP orchestrator_workflows_succeeded_total Total workflows succeeded\n"))
		w.Write([]byte("# TYPE orchestrator_workflows_succeeded_total counter\n"))
		w.Write([]byte("orchestrator_workflows_succeeded_total " +
			strconv.FormatInt(metrics.WorkflowsSucceeded, 10) + "\n"))
		w.Write([]byte("# HELP orchestrator_workflows_failed_total Total workflows failed\n"))
		w.Write([]byte("# TYPE orchestrator_workflows_failed_total counter\n"))
		w.Write([]byte("orchestrator_workflows_failed_total " +
			strconv.FormatInt(metrics.WorkflowsFailed, 10) + "\n"))
		w.Write([]byte("# HELP orchestrator_workflows_skipped_total Total workflows skipped due to lock\n"))
		w.Write([]byte("# TYPE orchestrator_workflows_skipped_total counter\n"))
		w.Write([]byte("orchestrator_workflows_skipped_total " +
			strconv.FormatInt(metrics.WorkflowsSkipped, 10) + "\n"))
	}
}

// Используется для предотвращения unused import warning
var _ = activities.Message{}
