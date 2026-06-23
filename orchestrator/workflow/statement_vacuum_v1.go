// statement_vacuum_v1: workflow тянет банковские выписки из compliance-ящика
// (label compliance-5458508), парсит, заливает в compliance-logic /statements/ingest,
// шлёт сводный WA-отбой клиенту о приёме.
//
// DEC-0027 Open Issue #1 — orchestrator-сторона statement-vacuum:
//   (a) label-прокидка в mail-активность ✅
//   (b) ingest-активность ✅
//   (c) этот workflow ← здесь
//   (d) wire в main.go + cron 4×/день МСК + handler /statement-vacuum-now → следующий шаг
//   (e) курсор last_polled_at → отложен; пока используем FallbackPeriodHours=24
package workflow

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"strings"
	"time"

	"github.com/ciriycpro/orchestrator/activities"
)

// StatementVacuumWorkflow — рецепт: mail → attachment → parser → ingest → WA-отбой.
type StatementVacuumWorkflow struct {
	Mail       *activities.MailActivity
	Attachment *activities.AttachmentActivity
	Parser     *activities.ParserActivity
	Ingest     *activities.IngestActivity
	WhatsApp   *activities.WhatsAppActivity
	Logger     *slog.Logger

	MailboxLabel        string // например, "compliance-5458508"
	WhatsAppNumber      string // получатель сводного отбоя, например "79266143959"
	FallbackPeriodHours int    // окно сканирования при отсутствии курсора (24)
}

// VacuumParams — параметры запуска workflow.
type VacuumParams struct {
	TraceID string
	Trigger TriggerSource
}

// VacuumResult — агрегированный итог одного прогона.
type VacuumResult struct {
	TraceID            string
	MessagesScanned    int
	AttachmentsTotal   int      // pdf/xlsx-вложений найдено
	StatementsIngested int      // успешно принято (alreadyIngested=false)
	StatementsSkipped  int      // dedup'нуто (alreadyIngested=true)
	OperationsCreated  int      // sum по успешным
	GapsClosed         int      // sum по успешным
	WhatsAppSent       bool
	Errors             []string // частичные ошибки — workflow продолжается при падении одного письма
}

// ingestMeta — meta-JSON для POST /statements/ingest.
// Контракт зафиксирован в StatementIngestService.ingest и smoke step_p4c_ingest.sh.
type ingestMeta struct {
	Account         string            `json:"account"`
	Bank            string            `json:"bank"`
	OwnerName       string            `json:"owner_name,omitempty"`
	PeriodStart     string            `json:"period_start"`
	PeriodEnd       string            `json:"period_end"`
	SourceMessageID string            `json:"source_message_id"`
	Operations      []json.RawMessage `json:"operations"`
}

// ingestSummary — позиция в сводном WA-отбое.
type ingestSummary struct {
	Bank        string
	PeriodStart string
	PeriodEnd   string
	OpsCreated  int
}

// Run — запуск одного прогона пылесоса.
// Принцип ошибок: одна выписка падает (download/parse/ingest) — пишем в Errors,
// продолжаем остальные. WA-отбой формируется только по успешным (!alreadyIngested).
func (w *StatementVacuumWorkflow) Run(ctx context.Context, params VacuumParams) (*VacuumResult, error) {
	logger := w.Logger.With(
		slog.String("workflow", "statement_vacuum_v1"),
		slog.String("trace_id", params.TraceID),
		slog.String("trigger", string(params.Trigger)),
	)
	result := &VacuumResult{TraceID: params.TraceID}
	opts := activities.CallOptions{TraceID: params.TraceID}

	// 1. since = now - FallbackPeriodHours (без курсора, отложено в (e))
	since := time.Now().UTC().Add(-time.Duration(w.FallbackPeriodHours) * time.Hour)
	sinceStr := since.Format("2006-01-02T15:04")
	logger.Info("step.fetch_mail.start",
		slog.String("since", sinceStr),
		slog.String("label", w.MailboxLabel))

	// 2. Mail с фильтром по label compliance-ящика
	messages, err := w.Mail.GetMailSince(ctx,
		activities.GetMailSinceParams{Since: sinceStr, Label: w.MailboxLabel}, opts)
	if err != nil {
		logger.Error("step.fetch_mail.fail", slog.String("error", err.Error()))
		return result, fmt.Errorf("fetch mail: %w", err)
	}
	result.MessagesScanned = len(messages)
	logger.Info("step.fetch_mail.done", slog.Int("count", len(messages)))

	if len(messages) == 0 {
		logger.Info("workflow.done", slog.String("reason", "no_messages"))
		return result, nil
	}

	// 3. По каждому сообщению: фильтр pdf/xlsx → Download → ParseStatement → Ingest
	successes := []ingestSummary{}

	for _, msg := range messages {
		for _, name := range msg.AttachmentNames {
			lower := strings.ToLower(name)
			if !strings.HasSuffix(lower, ".pdf") && !strings.HasSuffix(lower, ".xlsx") {
				continue
			}
			result.AttachmentsTotal++

			attLogger := logger.With(
				slog.String("message_id", msg.MessageID),
				slog.String("filename", name))

			// 3a. Download вложения (attachment-service сохраняет в /var/lib/mail-stack/attachments/)
			dl, err := w.Attachment.Download(ctx,
				activities.DownloadParams{MessageID: msg.MessageID, Filename: name, Label: w.MailboxLabel}, opts)
			if err != nil {
				attLogger.Warn("step.download.fail", slog.String("error", err.Error()))
				result.Errors = append(result.Errors, fmt.Sprintf("download %s: %v", name, err))
				continue
			}

			// 3b. ParseStatement (422 от парсера = не банковская выписка, не считаем за error)
			parsed, err := w.Parser.ParseStatement(ctx,
				activities.ParseStatementParams{Path: dl.Path}, opts)
			if err != nil {
				attLogger.Info("step.parse.skip",
					slog.String("reason", "not_a_statement_or_parser_error"),
					slog.String("error", err.Error()))
				continue
			}

			// 3c. По каждому statement в файле — отдельный POST /statements/ingest
			for _, stmt := range parsed.Statements {
				meta := ingestMeta{
					Account:         stmt.Account,
					Bank:            parsed.Bank,
					OwnerName:       stmt.OwnerName,
					PeriodStart:     stmt.PeriodStart,
					PeriodEnd:       stmt.PeriodEnd,
					SourceMessageID: msg.MessageID,
					Operations:      stmt.Operations,
				}
				metaJSON, mErr := json.Marshal(meta)
				if mErr != nil {
					attLogger.Warn("step.meta.fail", slog.String("error", mErr.Error()))
					result.Errors = append(result.Errors,
						fmt.Sprintf("marshal meta %s: %v", stmt.Account, mErr))
					continue
				}

				r, err := w.Ingest.Ingest(ctx, dl.Path, string(metaJSON), opts)
				if err != nil {
					attLogger.Warn("step.ingest.fail", slog.String("error", err.Error()))
					result.Errors = append(result.Errors,
						fmt.Sprintf("ingest %s: %v", stmt.Account, err))
					continue
				}

				attLogger.Info("step.ingest.done",
					slog.String("client_inn", r.ClientInn),
					slog.String("account", r.AccountNumber),
					slog.Bool("already_ingested", r.AlreadyIngested),
					slog.Int("ops_created", r.OpsCreated),
					slog.Int("ops_skipped", r.OpsSkipped),
					slog.Int("gaps_closed", r.GapsClosed))

				if r.AlreadyIngested {
					result.StatementsSkipped++
				} else {
					result.StatementsIngested++
					result.OperationsCreated += r.OpsCreated
					result.GapsClosed += r.GapsClosed
					successes = append(successes, ingestSummary{
						Bank:        parsed.Bank,
						PeriodStart: stmt.PeriodStart,
						PeriodEnd:   stmt.PeriodEnd,
						OpsCreated:  r.OpsCreated,
					})
				}
			}
		}
	}

	// 4. Сводный WA-отбой (если есть что слать и есть номер).
	// Timeout встроен в WhatsAppActivity (~150s, см. wire в main.go).
	if len(successes) > 0 && w.WhatsAppNumber != "" {
		text := formatVacuumSummary(successes)
		err := w.WhatsApp.SendMessage(ctx,
			activities.SendWAParams{Number: w.WhatsAppNumber, Text: text}, opts)
		if err != nil {
			logger.Warn("step.wa_summary.fail", slog.String("error", err.Error()))
			result.Errors = append(result.Errors, fmt.Sprintf("wa summary: %v", err))
		} else {
			result.WhatsAppSent = true
			logger.Info("step.wa_summary.done", slog.Int("statements", len(successes)))
		}
	}

	logger.Info("workflow.done",
		slog.Int("messages_scanned", result.MessagesScanned),
		slog.Int("attachments_total", result.AttachmentsTotal),
		slog.Int("statements_ingested", result.StatementsIngested),
		slog.Int("statements_skipped", result.StatementsSkipped),
		slog.Int("operations_created", result.OperationsCreated),
		slog.Int("gaps_closed", result.GapsClosed),
		slog.Bool("wa_sent", result.WhatsAppSent),
		slog.Int("errors", len(result.Errors)))

	return result, nil
}

// formatVacuumSummary — текст сводного WA-отбоя.
// Шаблон зафиксирован Артёмом 10.06: без обращения, без про gaps.
func formatVacuumSummary(items []ingestSummary) string {
	bankRu := map[string]string{
		"vtb":  "ВТБ",
		"alfa": "Альфа-Банк",
	}
	var sb strings.Builder
	sb.WriteString("✅ Получены выписки и приняты в работу:\n")
	for _, it := range items {
		bank := bankRu[strings.ToLower(it.Bank)]
		if bank == "" {
			bank = it.Bank
		}
		period := formatPeriod(it.PeriodStart, it.PeriodEnd)
		sb.WriteString(fmt.Sprintf("• %s, период %s (%d %s)\n",
			bank, period, it.OpsCreated,
			pluralRu(it.OpsCreated, "операция", "операции", "операций")))
	}
	return strings.TrimRight(sb.String(), "\n")
}

// formatPeriod — "2026-03-30" + "2026-06-08" → "30.03–08.06.2026".
// Если года разные — оба показываем: "30.12.2025–08.06.2026".
func formatPeriod(startISO, endISO string) string {
	start, errS := time.Parse("2006-01-02", startISO)
	end, errE := time.Parse("2006-01-02", endISO)
	if errS != nil || errE != nil {
		return startISO + "–" + endISO
	}
	if start.Year() == end.Year() {
		return fmt.Sprintf("%02d.%02d–%02d.%02d.%d",
			start.Day(), start.Month(), end.Day(), end.Month(), end.Year())
	}
	return fmt.Sprintf("%02d.%02d.%d–%02d.%02d.%d",
		start.Day(), start.Month(), start.Year(),
		end.Day(), end.Month(), end.Year())
}
