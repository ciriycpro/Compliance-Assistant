// Package workflow — оркестрация цепочки mail-stack.
// КРИТИЧНО (DEC-014): этот код переписывается при миграции на Temporal v2 / KAMF v3.
// Activities из ../activities/ переиспользуются на 80-100%.
package workflow

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"sync"
	"time"

	"github.com/ciriycpro/orchestrator/activities"
)

// EmailDigestWorkflow — основная цепочка для дайджеста.
type EmailDigestWorkflow struct {
	Mail        *activities.MailActivity
	Attachment  *activities.AttachmentActivity
	Parser      *activities.ParserActivity
	Summary     *activities.SummaryActivity
	SummaryPrep *activities.SummaryPrepActivity // DEC-0030: дистилляция длинных документов
	Telegram    *activities.TelegramActivity
	WhatsApp    *activities.WhatsAppActivity
	State       *activities.StateActivity
	Notify      *activities.NotifyActivity // push к Agent Caller о завершении workflow (для отмены UX-таймеров)
	Logger      *slog.Logger

	// Конфиг доставки
	TelegramChatID string // дефолтный chat для cron-trigger
	WhatsAppNumber string // если пустой — WA-пинг отключён

	// Lock TTL для защиты workflow от двойного клика (секунды)
	LockTTLSeconds int

	// Fallback period когда last_at не записан (для первого запуска)
	FallbackPeriodHours int

	// DEC-0030: Summary-prep флаги
	// EnableSummaryPrep — true = после parser длинные вложения дистиллируются через
	// summary-prep, false = старый workflow (raw text напрямую в summary-service).
	EnableSummaryPrep bool
	// DistillThresholdChars — порог длины текста для запуска дистилляции.
	// Если 0 — берём 80000 по умолчанию.
	DistillThresholdChars int
}

// TriggerSource — источник запуска workflow (для product-grade логики).
type TriggerSource string

const (
	TriggerCron   TriggerSource = "cron"   // расписание → WA + Telegram
	TriggerButton TriggerSource = "button" // кнопка → только Telegram (пользователь уже в Telegram)
)

// RunParams — параметры выполнения.
type RunParams struct {
	TraceID      string
	Trigger      TriggerSource // обязательно: cron или button
	ChatID       string        // куда доставлять (если пустой — TelegramChatID из config)
	ForceRefresh bool          // игнорировать lock (для отладки)
}

// RunResult — итог выполнения.
type RunResult struct {
	TraceID          string
	MessagesCount    int
	AttachmentsCount int
	Digest           *activities.DigestResult
	StartedAt        time.Time
	FinishedAt       time.Time
	Errors           []string
	SkippedDueLock   bool      // true если workflow не стартовал из-за активного lock
	PeriodSince      time.Time // начало периода фактическое
	PeriodUntil      time.Time // конец периода (now() на момент старта)
	PeriodSource     string    // "state" — взят из state-service, "fallback" — fallback период
}

// ErrLockHeld — алиас для удобства проверки в server.
var ErrLockHeld = activities.ErrLockHeld

// Run — основная цепочка mail → attachment → parser → summary → telegram с incremental state logic.
//
// Поведение по DEC-013:
//  1. AcquireLock через state-service (если 409 — workflow.SkippedDueLock=true, exit)
//  2. Прочитать last_at → если 404 → fallback `FallbackPeriodHours` назад
//  3. period = (now() - last_at)
//  4. Workflow выполняется
//  5. После успешной Telegram-доставки → SetLastAt(now()) + ReleaseLock
//  6. При любых ошибках → ReleaseLock через defer
func (w *EmailDigestWorkflow) Run(ctx context.Context, params RunParams) (*RunResult, error) {
	logger := w.Logger.With(
		slog.String("trace_id", params.TraceID),
		slog.String("workflow", "email_digest_v1"),
		slog.String("trigger", string(params.Trigger)),
	)
	result := &RunResult{
		TraceID:   params.TraceID,
		StartedAt: time.Now().UTC(),
	}

	// notifyStatus — финальный статус для Agent Caller. Меняется по ходу workflow.
	// defer гарантирует что NotifyDone будет вызван НА ЛЮБОМ return (включая panic).
	// Это критично: если оставить UX-таймеры висеть, бот будет писать "разбираюсь" после того
	// как пользователь уже получил дайджест (или после ошибки).
	notifyStatus := "failed" // дефолт: если упадём до явной установки — статус failed
	defer func() {
		w.notifyDoneSafe(ctx, params.TraceID, chatIDStrForDefer(params.ChatID, w.TelegramChatID), notifyStatus, logger)
	}()

	// Дефолтный chat_id если не передан
	chatIDStr := params.ChatID
	if chatIDStr == "" {
		chatIDStr = w.TelegramChatID
	}
	chatID, err := activities.ParseChatID(chatIDStr)
	if err != nil {
		logger.Error("workflow.invalid_chat_id", slog.String("chat_id", chatIDStr), slog.String("error", err.Error()))
		return result, fmt.Errorf("invalid chat_id: %w", err)
	}
	logger = logger.With(slog.Int64("chat_id", chatID))

	// ============================================================
	// 1. AcquireLock — защита от двойного запуска
	// ============================================================
	if !params.ForceRefresh {
		err := w.State.AcquireLock(ctx, activities.AcquireLockParams{
			ChatID:     chatID,
			TTLSeconds: w.LockTTLSeconds,
		}, activities.CallOptions{TraceID: params.TraceID})
		if errors.Is(err, activities.ErrLockHeld) {
			logger.Info("workflow.skipped.lock_held")
			result.SkippedDueLock = true
			result.FinishedAt = time.Now().UTC()
			notifyStatus = "lock_held" // defer вызовет NotifyDone
			return result, nil
		}
		if err != nil {
			logger.Error("workflow.acquire_lock.fail", slog.String("error", err.Error()))
			return result, fmt.Errorf("acquire lock: %w", err)
		}
		logger.Info("workflow.lock.acquired", slog.Int("ttl_sec", w.LockTTLSeconds))
		// Гарантированный release через defer (даже если panic)
		defer func() {
			releaseCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()
			if err := w.State.ReleaseLock(releaseCtx, chatID, activities.CallOptions{TraceID: params.TraceID}); err != nil {
				logger.Warn("workflow.release_lock.fail", slog.String("error", err.Error()))
			}
		}()
	}

	// ============================================================
	// 2. Read last_at → определить period
	// ============================================================
	now := time.Now().UTC()
	var since time.Time
	lastAt, err := w.State.GetLastAt(ctx, activities.GetLastAtParams{ChatID: chatID},
		activities.CallOptions{TraceID: params.TraceID})
	if errors.Is(err, activities.ErrStateNotFound) {
		since = now.Add(-time.Duration(w.FallbackPeriodHours) * time.Hour)
		result.PeriodSource = "fallback"
		logger.Info("workflow.period.fallback",
			slog.Int("fallback_hours", w.FallbackPeriodHours),
			slog.String("since", since.Format(time.RFC3339)),
		)
	} else if err != nil {
		logger.Error("workflow.get_last_at.fail", slog.String("error", err.Error()))
		return result, fmt.Errorf("get last_at: %w", err)
	} else {
		since = lastAt
		result.PeriodSource = "state"
		logger.Info("workflow.period.incremental",
			slog.String("since", since.Format(time.RFC3339)),
			slog.Duration("delta", now.Sub(since)),
		)
	}
	result.PeriodSince = since
	result.PeriodUntil = now

	// Mail-service ожидает YYYY-MM-DDTHH:MM
	sinceStr := since.Format("2006-01-02T15:04")

	// ============================================================
	// 3. Fetch mail
	// ============================================================
	logger.Info("step.fetch_mail.start", slog.String("since", sinceStr))

	messages, err := w.Mail.GetMailSince(ctx, activities.GetMailSinceParams{
		Since: sinceStr,
	}, activities.CallOptions{TraceID: params.TraceID})
	if err != nil {
		logger.Error("step.fetch_mail.fail", slog.String("error", err.Error()))
		return result, fmt.Errorf("fetch mail: %w", err)
	}
	logger.Info("step.fetch_mail.done", slog.Int("count", len(messages)))
	result.MessagesCount = len(messages)

	if len(messages) == 0 {
		logger.Info("workflow.no_messages")
		// Доставляем "ничего нового"
		_ = w.deliverNoMessages(ctx, params.TraceID, chatIDStr, since, now)
		// last_at обновляем — даже когда писем нет, проверка реально была
		if err := w.State.SetLastAt(ctx, activities.SetLastAtParams{
			ChatID:    chatID,
			Timestamp: now,
		}, activities.CallOptions{TraceID: params.TraceID}); err != nil {
			logger.Warn("workflow.set_last_at.fail", slog.String("error", err.Error()))
		}
		result.FinishedAt = time.Now().UTC()
		notifyStatus = "no_messages" // defer вызовет NotifyDone
		return result, nil
	}

	// ============================================================
	// 4. Download + Parse вложений
	// ============================================================
	// Подсчитываем общее количество вложений ДО цикла — для прогресс-события
	totalAttachments := 0
	for _, m := range messages {
		totalAttachments += len(m.AttachmentNames)
	}

	// Emit прогресс-событие: attachments_start (Agent Caller сам решит показать или нет)
	if totalAttachments > 0 {
		w.notifyProgressSafe(ctx, params.TraceID, chatIDStr, "attachments_start",
			map[string]any{"count": totalAttachments}, logger)
	}

	for i := range messages {
		msg := &messages[i]
		for _, filename := range msg.AttachmentNames {
			callOpts := activities.CallOptions{TraceID: params.TraceID}

			dl, err := w.Attachment.Download(ctx, activities.DownloadParams{
				MessageID: msg.MessageID,
				Filename:  filename,
			}, callOpts)
			if err != nil {
				logger.Warn("step.download.fail",
					slog.String("messageId", msg.MessageID),
					slog.String("filename", filename),
					slog.String("error", err.Error()),
				)
				result.Errors = append(result.Errors, fmt.Sprintf("download %s: %v", filename, err))
				continue
			}
			result.AttachmentsCount++

			parsed, err := w.Parser.Parse(ctx, activities.ParseParams{
				Path: dl.Path,
			}, callOpts)
			if err != nil {
				logger.Warn("step.parse.fail",
					slog.String("path", dl.Path),
					slog.String("error", err.Error()),
				)
				result.Errors = append(result.Errors, fmt.Sprintf("parse %s: %v", filename, err))
				msg.ParsedAttachments = append(msg.ParsedAttachments, activities.ParsedAttachment{
					Filename: filename,
					Text:     fmt.Sprintf("[не удалось распарсить: %v]", err),
					Method:   "error",
				})
				continue
			}

			msg.ParsedAttachments = append(msg.ParsedAttachments, activities.ParsedAttachment{
				Filename: filename,
				Text:     parsed.Text,
				Method:   parsed.Method,
				Format:   parsed.Format,
				Warnings: parsed.Warnings,
			})
		}
	}
	logger.Info("step.attachments.done", slog.Int("count", result.AttachmentsCount))

	// ============================================================
	// 4.5. Distill attachments (DEC-0030)
	// ============================================================
	// Для каждого parsed_attachment с длинным текстом (> DistillThresholdChars)
	// — параллельный вызов summary-prep:8772/distill.
	// Результат записывается в ParsedAttachment.DistillResult.
	// При ошибке — продолжаем с raw text (best-effort, не критический путь).
	// Активируется только если w.EnableSummaryPrep=true и SummaryPrep клиент проставлен.
	if w.EnableSummaryPrep && w.SummaryPrep != nil {
		threshold := w.DistillThresholdChars
		if threshold <= 0 {
			threshold = 80000
		}

		// Собираем список (msg_idx, att_idx) для дистилляции
		type distillTarget struct {
			msgIdx int
			attIdx int
		}
		var targets []distillTarget
		for mi := range messages {
			for ai := range messages[mi].ParsedAttachments {
				if len(messages[mi].ParsedAttachments[ai].Text) > threshold {
					targets = append(targets, distillTarget{msgIdx: mi, attIdx: ai})
				}
			}
		}

		if len(targets) > 0 {
			logger.Info("step.distill.start",
				slog.Int("count", len(targets)),
				slog.Int("threshold_chars", threshold),
			)

			// Параллельный вызов
			var wg sync.WaitGroup
			var muErr sync.Mutex
			distillErrors := []string{}

			for _, t := range targets {
				wg.Add(1)
				go func(t distillTarget) {
					defer wg.Done()
					msg := &messages[t.msgIdx]
					att := &msg.ParsedAttachments[t.attIdx]

					distillResult, err := w.SummaryPrep.Distill(ctx, activities.DistillParams{
						Text:               att.Text,
						From:               msg.From,
						Subject:            msg.Subject,
						Date:               msg.Date,
						Filename:           att.Filename,
						QualityMode:        "fast",
						ContractStrictness: "soft",
					}, activities.CallOptions{TraceID: params.TraceID})
					if err != nil {
						logger.Warn("step.distill.attachment.fail",
							slog.String("filename", att.Filename),
							slog.Int("text_len", len(att.Text)),
							slog.String("error", err.Error()),
						)
						muErr.Lock()
						distillErrors = append(distillErrors, fmt.Sprintf("distill %s: %v", att.Filename, err))
						muErr.Unlock()
						return
					}

					att.DistillResult = distillResult
				}(t)
			}
			wg.Wait()

			distilledCount := 0
			for _, t := range targets {
				if messages[t.msgIdx].ParsedAttachments[t.attIdx].DistillResult != nil {
					distilledCount++
				}
			}

			result.Errors = append(result.Errors, distillErrors...)
			logger.Info("step.distill.done",
				slog.Int("attempted", len(targets)),
				slog.Int("succeeded", distilledCount),
				slog.Int("failed", len(distillErrors)),
			)
		} else {
			logger.Info("step.distill.skip", slog.String("reason", "no long attachments"))
		}
	} else {
		logger.Debug("step.distill.disabled",
			slog.Bool("enable_summary_prep", w.EnableSummaryPrep),
			slog.Bool("client_set", w.SummaryPrep != nil),
		)
	}

	// ============================================================
	// 5. Summary
	// ============================================================
	periodStr := buildPeriodDescription(since, now, result.PeriodSource)
	logger.Info("step.summary.start", slog.String("period", periodStr))

	// Emit прогресс-событие: summary_start. Передаём elapsed_ms — Agent Caller сам решит
	// показывать ли пользователю (по порогу). Это позволяет менять UX-правила без правки orchestrator.
	elapsedMs := time.Since(result.StartedAt).Milliseconds()
	w.notifyProgressSafe(ctx, params.TraceID, chatIDStr, "summary_start",
		map[string]any{"elapsed_ms": elapsedMs}, logger)

	digest, err := w.Summary.Summarize(ctx, activities.SummarizeParams{
		Period:   periodStr,
		Messages: messages,
	}, activities.CallOptions{TraceID: params.TraceID, Timeout: 120 * time.Second})
	if err != nil {
		logger.Error("step.summary.fail", slog.String("error", err.Error()))
		// Доставляем ошибку в Telegram чтобы видно было
		_ = w.deliverError(ctx, params.TraceID, chatIDStr, fmt.Sprintf("Дайджест не сделан: %v", err))
		return result, fmt.Errorf("summarize: %w", err)
	}
	result.Digest = digest

	logger.Info("step.summary.done",
		slog.String("model", digest.Model),
		slog.Bool("fallback_used", digest.FallbackUsed),
		slog.Int("tokens_in", digest.TokensIn),
		slog.Int("tokens_out", digest.TokensOut),
		slog.Float64("cost_usd", digest.CostUSD),
	)

	// ============================================================
	// 6. WhatsApp pre-alert (только для cron, не для кнопки)
	// ============================================================
	if params.Trigger == TriggerCron && w.WhatsAppNumber != "" && w.WhatsApp != nil {
		logger.Info("step.wa_ping.start")
		waText := fmt.Sprintf("👋 Привет! На почту пришли письма (%d шт), открой Telegram — я подготовил для тебя обзор.", result.MessagesCount)
		waCtx, waCancel := context.WithTimeout(ctx, 150*time.Second)
		if err := w.WhatsApp.SendMessage(waCtx, activities.SendWAParams{
			Number: w.WhatsAppNumber,
			Text:   waText,
		}, activities.CallOptions{TraceID: params.TraceID, Timeout: 150 * time.Second}); err != nil {
			logger.Warn("step.wa_ping.fail", slog.String("error", err.Error()))
			result.Errors = append(result.Errors, fmt.Sprintf("whatsapp: %v", err))
		} else {
			logger.Info("step.wa_ping.done")
		}
		waCancel()
	} else {
		logger.Info("step.wa_ping.skip", slog.String("reason", string(params.Trigger)))
	}

	// ============================================================
	// 7. Telegram delivery
	// ============================================================
	logger.Info("step.deliver.start")
	deliveredOK := false
	if err := w.Telegram.SendMessage(ctx, activities.SendMessageParams{
		ChatID: chatIDStr,
		Text:   digest.SummaryTelegram,
	}, activities.CallOptions{TraceID: params.TraceID}); err != nil {
		logger.Error("step.deliver.fail", slog.String("error", err.Error()))
		result.Errors = append(result.Errors, fmt.Sprintf("telegram: %v", err))
	} else {
		logger.Info("step.deliver.done")
		deliveredOK = true
	}

	// ============================================================
	// 8. Обновление last_at — ТОЛЬКО при успешной доставке
	// ============================================================
	if deliveredOK {
		if err := w.State.SetLastAt(ctx, activities.SetLastAtParams{
			ChatID:    chatID,
			Timestamp: now,
		}, activities.CallOptions{TraceID: params.TraceID}); err != nil {
			logger.Warn("workflow.set_last_at.fail", slog.String("error", err.Error()))
			result.Errors = append(result.Errors, fmt.Sprintf("set_last_at: %v", err))
		} else {
			logger.Info("workflow.last_at.updated", slog.String("last_at", now.Format(time.RFC3339)))
		}
	} else {
		logger.Warn("workflow.last_at.not_updated", slog.String("reason", "delivery_failed"))
	}

	result.FinishedAt = time.Now().UTC()
	duration := result.FinishedAt.Sub(result.StartedAt)
	logger.Info("workflow.done",
		slog.Duration("duration", duration),
		slog.Int("messages", result.MessagesCount),
		slog.Int("attachments", result.AttachmentsCount),
		slog.Int("soft_errors", len(result.Errors)),
		slog.String("period_source", result.PeriodSource),
	)

	// notifyStatus финальный — defer вызовет NotifyDone
	if deliveredOK {
		notifyStatus = "delivered"
	} else {
		notifyStatus = "failed"
	}

	return result, nil
}

// chatIDStrForDefer — резолвит chatIDStr для defer-обработки.
// Defer вызывается ДО объявления локальной chatIDStr выше в функции, поэтому используем
// params.ChatID + dialogue с fallback на default.
func chatIDStrForDefer(paramsChatID, defaultChatID string) string {
	if paramsChatID != "" {
		return paramsChatID
	}
	return defaultChatID
}

// notifyDoneSafe — отправляет уведомление Agent Caller о завершении workflow.
// Безопасный: ошибка не блокирует workflow, только Warning в логи.
// Используется во всех точках завершения через defer.
func (w *EmailDigestWorkflow) notifyDoneSafe(ctx context.Context, traceID, chatID, status string, logger *slog.Logger) {
	if w.Notify == nil {
		return // Notify не настроен (не должен быть в production)
	}
	if chatID == "" {
		// Если invalid chat_id попалось до парсинга — нечего уведомлять
		return
	}
	// Используем background context (не ctx) — workflow может быть отменён, но notify должен дойти
	notifyCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := w.Notify.WorkflowDone(notifyCtx, activities.WorkflowDoneParams{
		ChatID:  chatID,
		TraceID: traceID,
		Status:  status,
	}, activities.CallOptions{TraceID: traceID}); err != nil {
		logger.Warn("workflow.notify_done.fail",
			slog.String("error", err.Error()),
			slog.String("status", status),
		)
		return
	}
	logger.Info("workflow.notify_done.ok", slog.String("status", status))
}

// notifyProgressSafe — отправляет прогресс-событие Agent Caller.
//
// Семантика: orchestrator говорит "я сейчас вот на каком этапе" с метаданными.
// Agent Caller сам решает что показать пользователю (по step + meta).
// Это позволяет менять UX-логику и пороги без правки orchestrator.
//
// Не блокирует workflow на ошибках — только Warning в логи. Если Agent Caller недоступен —
// прогресс-сообщение просто не покажется, юзер дождётся финального дайджеста.
func (w *EmailDigestWorkflow) notifyProgressSafe(ctx context.Context, traceID, chatID, step string, meta map[string]any, logger *slog.Logger) {
	if w.Notify == nil {
		return
	}
	if chatID == "" {
		return
	}
	// Короткий timeout — прогресс-событие не должно блокировать основной workflow надолго
	notifyCtx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	if err := w.Notify.WorkflowProgress(notifyCtx, activities.WorkflowProgressParams{
		ChatID:  chatID,
		TraceID: traceID,
		Step:    step,
		Meta:    meta,
	}, activities.CallOptions{TraceID: traceID}); err != nil {
		logger.Warn("workflow.notify_progress.fail",
			slog.String("error", err.Error()),
			slog.String("step", step),
		)
		return
	}
	logger.Info("workflow.notify_progress.ok",
		slog.String("step", step),
		slog.Any("meta", meta),
	)
}

// buildPeriodDescription — человеческое описание периода для промпта summary.
// Варианты:
//
//	"за последние сутки"               — fallback (первый запуск или после рестарта)
//	"с момента предыдущего обзора (X)" — incremental
func buildPeriodDescription(since, until time.Time, source string) string {
	delta := until.Sub(since)
	human := humanizeDuration(delta)

	if source == "fallback" {
		return fmt.Sprintf("за последние %s", human)
	}
	return fmt.Sprintf("с момента предыдущего обзора (%s назад)", human)
}

// humanizeDuration — преобразует duration в "4 часа 30 минут" / "2 дня" / "15 минут".
func humanizeDuration(d time.Duration) string {
	if d < time.Minute {
		return "несколько секунд"
	}

	days := int(d.Hours() / 24)
	hours := int(d.Hours()) % 24
	minutes := int(d.Minutes()) % 60

	parts := []string{}
	if days > 0 {
		parts = append(parts, fmt.Sprintf("%d %s", days, pluralRu(days, "день", "дня", "дней")))
	}
	if hours > 0 {
		parts = append(parts, fmt.Sprintf("%d %s", hours, pluralRu(hours, "час", "часа", "часов")))
	}
	if minutes > 0 && days == 0 {
		parts = append(parts, fmt.Sprintf("%d %s", minutes, pluralRu(minutes, "минуту", "минуты", "минут")))
	}
	if len(parts) == 0 {
		return "несколько минут"
	}
	if len(parts) == 1 {
		return parts[0]
	}
	return parts[0] + " " + parts[1]
}

// pluralRu — правильное склонение для русских числительных.
func pluralRu(n int, one, few, many string) string {
	n = abs(n)
	mod10 := n % 10
	mod100 := n % 100
	switch {
	case mod10 == 1 && mod100 != 11:
		return one
	case mod10 >= 2 && mod10 <= 4 && (mod100 < 10 || mod100 >= 20):
		return few
	default:
		return many
	}
}

func abs(n int) int {
	if n < 0 {
		return -n
	}
	return n
}

// deliverNoMessages — отправить "ничего нового" в Telegram.
func (w *EmailDigestWorkflow) deliverNoMessages(ctx context.Context, traceID, chatID string, since, until time.Time) error {
	human := humanizeDuration(until.Sub(since))
	text := fmt.Sprintf("📭 За последние %s новых писем нет.", human)
	return w.Telegram.SendMessage(ctx, activities.SendMessageParams{
		ChatID: chatID,
		Text:   text,
	}, activities.CallOptions{TraceID: traceID})
}

// deliverError — отправить error в Telegram (для debugging).
func (w *EmailDigestWorkflow) deliverError(ctx context.Context, traceID, chatID, msg string) error {
	text := fmt.Sprintf("⚠️ %s", msg)
	return w.Telegram.SendMessage(ctx, activities.SendMessageParams{
		ChatID: chatID,
		Text:   text,
	}, activities.CallOptions{TraceID: traceID})
}
