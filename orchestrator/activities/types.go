// Package activities — бизнес-логика взаимодействия с микросервисами mail-stack.
// КРИТИЧНО (DEC-014): этот код переиспользуется на v2 (Temporal activities) и v3 (KAMF NodeHandlers).
// При написании ВСЕГДА думать: "как этот код будет вызван из Temporal Workflow?".
package activities

import (
	"context"
	"net/http"
	"time"
)

// Common-структуры для всех activities.

// Message — представление письма от mail-service.
// Структура минимальна — orchestrator не лезет внутрь, только передаёт дальше.
type Message struct {
	MessageID        string         `json:"messageId"`
	From             string         `json:"from"`
	Email            string         `json:"email,omitempty"`
	FIO              string         `json:"fio,omitempty"`
	Subject          string         `json:"subject"`
	Date             string         `json:"date"`
	BodyText         string         `json:"body_text,omitempty"`
	AttachmentNames  []string       `json:"attachment_names,omitempty"`
	ParsedAttachments []ParsedAttachment `json:"parsed_attachments,omitempty"`
}

// ParsedAttachment — результат парсинга вложения через parser-service.
type ParsedAttachment struct {
	Filename string   `json:"filename"`
	Text     string   `json:"text"`
	Method   string   `json:"method,omitempty"`
	Format   string   `json:"format,omitempty"`
	Warnings []string `json:"warnings,omitempty"`
}

// DigestResult — итоговый дайджест от summary-service.
type DigestResult struct {
	SummaryMarkdown string  `json:"summary_markdown"`
	SummaryTelegram string  `json:"summary_telegram"`
	TokensIn        int     `json:"tokens_in"`
	TokensOut       int     `json:"tokens_out"`
	CostUSD         float64 `json:"cost_usd"`
	Model           string  `json:"model"`
	FallbackUsed    bool    `json:"fallback_used"`
}

// newHTTPClient — общий HTTP-клиент с timeout.
func newHTTPClient(timeout time.Duration) *http.Client {
	return &http.Client{
		Timeout: timeout,
	}
}

// CallOptions — параметры вызова activity (передаются из workflow).
type CallOptions struct {
	TraceID string        // X-Trace-Id header (correlation)
	Timeout time.Duration // override default timeout
}

// Default timeout если CallOptions.Timeout = 0.
func resolveTimeout(opts CallOptions, def time.Duration) time.Duration {
	if opts.Timeout > 0 {
		return opts.Timeout
	}
	return def
}

// Context-helper для активити (на будущее — для cancellation).
type ctxKey string

const (
	ctxKeyTraceID ctxKey = "trace_id"
)

func WithTraceID(ctx context.Context, traceID string) context.Context {
	return context.WithValue(ctx, ctxKeyTraceID, traceID)
}

func TraceIDFromContext(ctx context.Context) string {
	if v, ok := ctx.Value(ctxKeyTraceID).(string); ok {
		return v
	}
	return ""
}
