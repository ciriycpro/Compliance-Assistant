// State activity: клиент к state-service для incremental workflow logic.
// state-service хранит per-chat_id:
//   - last_at (ISO 8601) — время последнего успешного дайджеста
//   - lock (с TTL) — защита от двойного клика
package activities

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"time"
)

// ErrLockHeld — возвращается AcquireLock когда lock уже занят.
var ErrLockHeld = errors.New("state: lock already held")

// ErrStateNotFound — возвращается GetLastAt когда last_at ещё не записан.
var ErrStateNotFound = errors.New("state: not found")

// StateActivity — клиент к state-service.
type StateActivity struct {
	BaseURL string // http://127.0.0.1:8770
	APIKey  string
	Timeout time.Duration
}

// NewStateActivity конструктор.
func NewStateActivity(baseURL, apiKey string, timeout time.Duration) *StateActivity {
	return &StateActivity{BaseURL: baseURL, APIKey: apiKey, Timeout: timeout}
}

// GetLastAtParams — параметры для GetLastAt.
type GetLastAtParams struct {
	ChatID int64
}

// LastAtResponse — ответ state-service.
type lastAtResponse struct {
	ChatID int64  `json:"chat_id"`
	LastAt string `json:"last_at"`
}

// GetLastAt — получить timestamp последнего успешного дайджеста.
// Возвращает ErrStateNotFound если ключ не установлен (404 от state-service).
func (s *StateActivity) GetLastAt(ctx context.Context, params GetLastAtParams, opts CallOptions) (time.Time, error) {
	endpoint := fmt.Sprintf("%s/state/%d/last_at", s.BaseURL, params.ChatID)
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
	if err != nil {
		return time.Time{}, fmt.Errorf("create request: %w", err)
	}
	req.Header.Set("X-API-Key", s.APIKey)
	if opts.TraceID != "" {
		req.Header.Set("X-Trace-Id", opts.TraceID)
	}

	client := newHTTPClient(resolveTimeout(opts, s.Timeout))
	resp, err := client.Do(req)
	if err != nil {
		return time.Time{}, fmt.Errorf("state-service: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode == http.StatusNotFound {
		return time.Time{}, ErrStateNotFound
	}
	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(resp.Body)
		return time.Time{}, fmt.Errorf("state-service returned %d: %s", resp.StatusCode, string(body))
	}

	var r lastAtResponse
	if err := json.NewDecoder(resp.Body).Decode(&r); err != nil {
		return time.Time{}, fmt.Errorf("decode response: %w", err)
	}
	t, err := time.Parse(time.RFC3339, r.LastAt)
	if err != nil {
		return time.Time{}, fmt.Errorf("parse last_at %q: %w", r.LastAt, err)
	}
	return t.UTC(), nil
}

// SetLastAtParams — параметры для SetLastAt.
type SetLastAtParams struct {
	ChatID    int64
	Timestamp time.Time // если IsZero — state-service подставит now()
}

// SetLastAt — записать timestamp успешного дайджеста.
func (s *StateActivity) SetLastAt(ctx context.Context, params SetLastAtParams, opts CallOptions) error {
	endpoint := fmt.Sprintf("%s/state/%d/last_at", s.BaseURL, params.ChatID)

	body := map[string]string{}
	if !params.Timestamp.IsZero() {
		body["timestamp"] = params.Timestamp.UTC().Format(time.RFC3339)
	}
	bodyBytes, err := json.Marshal(body)
	if err != nil {
		return fmt.Errorf("marshal: %w", err)
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, bytes.NewReader(bodyBytes))
	if err != nil {
		return fmt.Errorf("create request: %w", err)
	}
	req.Header.Set("X-API-Key", s.APIKey)
	req.Header.Set("Content-Type", "application/json")
	if opts.TraceID != "" {
		req.Header.Set("X-Trace-Id", opts.TraceID)
	}

	client := newHTTPClient(resolveTimeout(opts, s.Timeout))
	resp, err := client.Do(req)
	if err != nil {
		return fmt.Errorf("state-service: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		errBody, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("state-service returned %d: %s", resp.StatusCode, string(errBody))
	}
	return nil
}

// AcquireLockParams — параметры для AcquireLock.
type AcquireLockParams struct {
	ChatID     int64
	TTLSeconds int // если 0 — state-service использует default (300s)
}

// AcquireLock — попытаться захватить lock для workflow.
// Возвращает ErrLockHeld если lock уже захвачен другим workflow.
func (s *StateActivity) AcquireLock(ctx context.Context, params AcquireLockParams, opts CallOptions) error {
	endpoint := fmt.Sprintf("%s/state/%d/lock", s.BaseURL, params.ChatID)

	body := map[string]int{}
	if params.TTLSeconds > 0 {
		body["ttl_seconds"] = params.TTLSeconds
	}
	bodyBytes, _ := json.Marshal(body)

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, bytes.NewReader(bodyBytes))
	if err != nil {
		return fmt.Errorf("create request: %w", err)
	}
	req.Header.Set("X-API-Key", s.APIKey)
	req.Header.Set("Content-Type", "application/json")
	if opts.TraceID != "" {
		req.Header.Set("X-Trace-Id", opts.TraceID)
	}

	client := newHTTPClient(resolveTimeout(opts, s.Timeout))
	resp, err := client.Do(req)
	if err != nil {
		return fmt.Errorf("state-service: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode == http.StatusConflict {
		return ErrLockHeld
	}
	if resp.StatusCode != http.StatusOK {
		errBody, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("state-service returned %d: %s", resp.StatusCode, string(errBody))
	}
	return nil
}

// ReleaseLock — снять lock. Идемпотентен (вызов на несуществующий lock не падает).
func (s *StateActivity) ReleaseLock(ctx context.Context, chatID int64, opts CallOptions) error {
	endpoint := fmt.Sprintf("%s/state/%d/lock", s.BaseURL, chatID)
	req, err := http.NewRequestWithContext(ctx, http.MethodDelete, endpoint, nil)
	if err != nil {
		return fmt.Errorf("create request: %w", err)
	}
	req.Header.Set("X-API-Key", s.APIKey)
	if opts.TraceID != "" {
		req.Header.Set("X-Trace-Id", opts.TraceID)
	}

	client := newHTTPClient(resolveTimeout(opts, s.Timeout))
	resp, err := client.Do(req)
	if err != nil {
		return fmt.Errorf("state-service: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		errBody, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("state-service returned %d: %s", resp.StatusCode, string(errBody))
	}
	return nil
}

// HealthCheck — для startup readiness.
func (s *StateActivity) HealthCheck(ctx context.Context) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, s.BaseURL+"/health", nil)
	if err != nil {
		return err
	}
	client := newHTTPClient(5 * time.Second)
	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("state-service unhealthy: status=%d", resp.StatusCode)
	}
	return nil
}

// ParseChatID — helper для парсинга chat_id из строки (для config).
func ParseChatID(s string) (int64, error) {
	if s == "" {
		return 0, fmt.Errorf("chat_id is empty")
	}
	n, err := strconv.ParseInt(s, 10, 64)
	if err != nil {
		return 0, fmt.Errorf("parse chat_id %q: %w", s, err)
	}
	return n, nil
}
