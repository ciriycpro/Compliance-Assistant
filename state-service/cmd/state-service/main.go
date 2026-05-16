// state-service — микросервис состояния для mail-stack.
//
// Хранит per-chat_id:
//   - last_at: время последнего успешного дайджеста (для incremental workflow)
//   - lock:    защита от двойного клика на кнопку
//
// См. DEC-013 + DEC-022.
package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/ciriycpro/mail-stack/state-service/config"
	"github.com/ciriycpro/mail-stack/state-service/logging"
	"github.com/ciriycpro/mail-stack/state-service/server"
	"github.com/ciriycpro/mail-stack/state-service/store"
)

func main() {
	logger := logging.New("info")

	cfg, err := config.Load()
	if err != nil {
		logger.Error("config.fail", slog.String("error", err.Error()))
		os.Exit(2)
	}

	// Подключение к Redis
	st := store.New(cfg.RedisAddr, cfg.RedisPassword, cfg.RedisDB, cfg.RedisTimeout)
	defer func() { _ = st.Close() }()

	// Ping при старте — fail fast если Redis недоступен
	pingCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := st.Ping(pingCtx); err != nil {
		logger.Error("redis.ping.fail", slog.String("addr", cfg.RedisAddr), slog.String("error", err.Error()))
		os.Exit(3)
	}
	logger.Info("redis.connected", slog.String("addr", cfg.RedisAddr), slog.Int("db", cfg.RedisDB))

	// HTTP server
	srv := server.New(st, logger, cfg.APIKey, cfg.DefaultLockTTL)
	httpAddr := fmt.Sprintf("%s:%d", cfg.HTTPHost, cfg.HTTPPort)
	httpServer := &http.Server{
		Addr:              httpAddr,
		Handler:           srv.Handler(),
		ReadHeaderTimeout: 5 * time.Second,
	}

	// Graceful shutdown
	idleConnsClosed := make(chan struct{})
	go func() {
		stop := make(chan os.Signal, 1)
		signal.Notify(stop, syscall.SIGINT, syscall.SIGTERM)
		<-stop
		logger.Info("shutdown.start")

		shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer shutdownCancel()
		if err := httpServer.Shutdown(shutdownCtx); err != nil {
			logger.Error("shutdown.fail", slog.String("error", err.Error()))
		}
		close(idleConnsClosed)
	}()

	apiKeyPrefix := ""
	if cfg.LogAPIKeyPrefix && len(cfg.APIKey) >= 8 {
		apiKeyPrefix = cfg.APIKey[:8] + "..."
	}
	logger.Info("http.start",
		slog.String("addr", httpAddr),
		slog.String("redis", cfg.RedisAddr),
		slog.Duration("default_lock_ttl", cfg.DefaultLockTTL),
		slog.String("api_key_prefix", apiKeyPrefix),
	)

	if err := httpServer.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		logger.Error("http.fail", slog.String("error", err.Error()))
		os.Exit(4)
	}

	<-idleConnsClosed
	logger.Info("shutdown.done")
}
