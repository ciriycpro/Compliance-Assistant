// Package logging — structured logging через slog (JSON).
package logging

import (
	"log/slog"
	"os"
)

// New — создаёт JSON logger с заданным уровнем.
// level: "debug" | "info" | "warn" | "error".
func New(level string) *slog.Logger {
	var lvl slog.Level
	switch level {
	case "debug":
		lvl = slog.LevelDebug
	case "warn":
		lvl = slog.LevelWarn
	case "error":
		lvl = slog.LevelError
	default:
		lvl = slog.LevelInfo
	}

	handler := slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{
		Level: lvl,
	})
	return slog.New(handler)
}
