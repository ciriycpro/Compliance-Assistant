// Package server — HTTP API для state-service.
//
// Endpoints:
//
//	GET    /health                              — health check (без auth)
//	GET    /state/{chat_id}/last_at             — получить last_at
//	POST   /state/{chat_id}/last_at             — обновить last_at  body: {"timestamp":"..."}
//	POST   /state/{chat_id}/lock                — захватить lock    body: {"ttl_seconds":300}
//	DELETE /state/{chat_id}/lock                — снять lock
//	GET    /state/{chat_id}/status              — статус: last_at + locked + ttl
//
// Auth: X-API-Key header на все /state/* endpoints.
package server

import (
	"context"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/google/uuid"

	"github.com/ciriycpro/mail-stack/state-service/store"
)

// Server — HTTP сервер state-service.
type Server struct {
	store          *store.Store
	logger         *slog.Logger
	apiKey         string
	defaultLockTTL time.Duration
	mux            *http.ServeMux
}

// New — конструктор.
func New(s *store.Store, logger *slog.Logger, apiKey string, defaultLockTTL time.Duration) *Server {
	srv := &Server{
		store:          s,
		logger:         logger,
		apiKey:         apiKey,
		defaultLockTTL: defaultLockTTL,
		mux:            http.NewServeMux(),
	}
	srv.routes()
	return srv
}

// Handler — возвращает root http.Handler.
func (s *Server) Handler() http.Handler {
	return s.mux
}

func (s *Server) routes() {
	s.mux.HandleFunc("GET /health", s.handleHealth)
	s.mux.HandleFunc("GET /state/{chat_id}/last_at", s.authed(s.handleGetLastAt))
	s.mux.HandleFunc("POST /state/{chat_id}/last_at", s.authed(s.handleSetLastAt))
	s.mux.HandleFunc("POST /state/{chat_id}/lock", s.authed(s.handleAcquireLock))
	s.mux.HandleFunc("DELETE /state/{chat_id}/lock", s.authed(s.handleReleaseLock))
	s.mux.HandleFunc("GET /state/{chat_id}/status", s.authed(s.handleStatus))
}

// authed — middleware constant-time API-key check.
func (s *Server) authed(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		got := r.Header.Get("X-API-Key")
		if subtle.ConstantTimeCompare([]byte(got), []byte(s.apiKey)) != 1 {
			writeError(w, http.StatusUnauthorized, "unauthorized")
			return
		}
		// trace_id
		traceID := r.Header.Get("X-Trace-Id")
		if traceID == "" {
			traceID = uuid.Must(uuid.NewV7()).String()
		}
		ctx := context.WithValue(r.Context(), traceIDKey{}, traceID)
		next(w, r.WithContext(ctx))
	}
}

type traceIDKey struct{}

func getTraceID(ctx context.Context) string {
	if v, ok := ctx.Value(traceIDKey{}).(string); ok {
		return v
	}
	return ""
}

// --- handlers ---

func (s *Server) handleHealth(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), 2*time.Second)
	defer cancel()
	if err := s.store.Ping(ctx); err != nil {
		writeError(w, http.StatusServiceUnavailable, fmt.Sprintf("redis unreachable: %v", err))
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

func (s *Server) handleGetLastAt(w http.ResponseWriter, r *http.Request) {
	chatID, ok := parseChatID(w, r)
	if !ok {
		return
	}
	t, err := s.store.GetLastAt(r.Context(), chatID)
	if errors.Is(err, store.ErrNotFound) {
		writeError(w, http.StatusNotFound, "not_found")
		return
	}
	if err != nil {
		s.logger.Error("get_last_at.fail", slog.Int64("chat_id", chatID), slog.String("error", err.Error()), slog.String("trace_id", getTraceID(r.Context())))
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"chat_id": chatID,
		"last_at": t.Format(time.RFC3339),
	})
}

type setLastAtReq struct {
	Timestamp string `json:"timestamp"`
}

func (s *Server) handleSetLastAt(w http.ResponseWriter, r *http.Request) {
	chatID, ok := parseChatID(w, r)
	if !ok {
		return
	}
	var req setLastAtReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid json")
		return
	}
	var t time.Time
	if req.Timestamp == "" {
		t = time.Now().UTC()
	} else {
		parsed, err := time.Parse(time.RFC3339, req.Timestamp)
		if err != nil {
			writeError(w, http.StatusBadRequest, "timestamp must be RFC3339")
			return
		}
		t = parsed.UTC()
	}
	if err := s.store.SetLastAt(r.Context(), chatID, t); err != nil {
		s.logger.Error("set_last_at.fail", slog.Int64("chat_id", chatID), slog.String("error", err.Error()), slog.String("trace_id", getTraceID(r.Context())))
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	s.logger.Info("set_last_at.ok", slog.Int64("chat_id", chatID), slog.String("last_at", t.Format(time.RFC3339)), slog.String("trace_id", getTraceID(r.Context())))
	writeJSON(w, http.StatusOK, map[string]any{
		"chat_id": chatID,
		"last_at": t.Format(time.RFC3339),
	})
}

type lockReq struct {
	TTLSeconds int `json:"ttl_seconds"`
}

func (s *Server) handleAcquireLock(w http.ResponseWriter, r *http.Request) {
	chatID, ok := parseChatID(w, r)
	if !ok {
		return
	}
	var req lockReq
	// Body optional — пустой → дефолтный TTL
	if r.ContentLength > 0 {
		_ = json.NewDecoder(r.Body).Decode(&req)
	}
	ttl := s.defaultLockTTL
	if req.TTLSeconds > 0 {
		ttl = time.Duration(req.TTLSeconds) * time.Second
	}
	if err := s.store.AcquireLock(r.Context(), chatID, ttl); err != nil {
		if errors.Is(err, store.ErrLockHeld) {
			s.logger.Info("lock.held", slog.Int64("chat_id", chatID), slog.String("trace_id", getTraceID(r.Context())))
			writeError(w, http.StatusConflict, "lock_held")
			return
		}
		s.logger.Error("lock.fail", slog.Int64("chat_id", chatID), slog.String("error", err.Error()), slog.String("trace_id", getTraceID(r.Context())))
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	s.logger.Info("lock.acquired", slog.Int64("chat_id", chatID), slog.Duration("ttl", ttl), slog.String("trace_id", getTraceID(r.Context())))
	writeJSON(w, http.StatusOK, map[string]any{
		"chat_id":     chatID,
		"acquired":    true,
		"ttl_seconds": int(ttl.Seconds()),
	})
}

func (s *Server) handleReleaseLock(w http.ResponseWriter, r *http.Request) {
	chatID, ok := parseChatID(w, r)
	if !ok {
		return
	}
	if err := s.store.ReleaseLock(r.Context(), chatID); err != nil {
		s.logger.Error("unlock.fail", slog.Int64("chat_id", chatID), slog.String("error", err.Error()), slog.String("trace_id", getTraceID(r.Context())))
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	s.logger.Info("lock.released", slog.Int64("chat_id", chatID), slog.String("trace_id", getTraceID(r.Context())))
	writeJSON(w, http.StatusOK, map[string]any{
		"chat_id":  chatID,
		"released": true,
	})
}

func (s *Server) handleStatus(w http.ResponseWriter, r *http.Request) {
	chatID, ok := parseChatID(w, r)
	if !ok {
		return
	}
	resp := map[string]any{"chat_id": chatID}

	t, err := s.store.GetLastAt(r.Context(), chatID)
	if err != nil && !errors.Is(err, store.ErrNotFound) {
		s.logger.Error("status.last_at.fail", slog.String("error", err.Error()))
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	if errors.Is(err, store.ErrNotFound) {
		resp["last_at"] = nil
	} else {
		resp["last_at"] = t.Format(time.RFC3339)
	}

	locked, err := s.store.IsLocked(r.Context(), chatID)
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	resp["locked"] = locked

	if locked {
		ttl, err := s.store.LockTTL(r.Context(), chatID)
		if err != nil {
			writeError(w, http.StatusInternalServerError, err.Error())
			return
		}
		resp["lock_ttl_seconds"] = int(ttl.Seconds())
	}

	writeJSON(w, http.StatusOK, resp)
}

// --- utils ---

func parseChatID(w http.ResponseWriter, r *http.Request) (int64, bool) {
	raw := r.PathValue("chat_id")
	if strings.TrimSpace(raw) == "" {
		writeError(w, http.StatusBadRequest, "chat_id required")
		return 0, false
	}
	chatID, err := strconv.ParseInt(raw, 10, 64)
	if err != nil {
		writeError(w, http.StatusBadRequest, "chat_id must be integer")
		return 0, false
	}
	return chatID, true
}

func writeJSON(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(body)
}

func writeError(w http.ResponseWriter, status int, msg string) {
	writeJSON(w, status, map[string]string{"error": msg})
}
