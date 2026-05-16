// Package logging — structured logs через slog (Go 1.21+ stdlib).
// JSON-format с trace_id для каждого запроса. На v2 — заменяется на OpenTelemetry.
package logging

import (
	"log/slog"
	"os"

	"github.com/google/uuid"
)

// Init — инициализирует глобальный slog-handler в JSON-формате.
// Все логи orchestrator идут в stdout, journald их подбирает.
func Init() *slog.Logger {
	handler := slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{
		Level: slog.LevelInfo,
	})
	logger := slog.New(handler).With(slog.String("service", "orchestrator"))
	slog.SetDefault(logger)
	return logger
}

// NewTraceID — UUID v7 (time-ordered, по DEC-014).
// Префикс времени упрощает correlation в логах.
func NewTraceID() string {
	u, err := uuid.NewV7()
	if err != nil {
		// Fallback на v4 если v7 не работает (старая ОС, etc)
		return uuid.NewString()
	}
	return u.String()
}

// WithTrace — возвращает logger с trace_id-полем.
func WithTrace(logger *slog.Logger, traceID string) *slog.Logger {
	return logger.With(slog.String("trace_id", traceID))
}
