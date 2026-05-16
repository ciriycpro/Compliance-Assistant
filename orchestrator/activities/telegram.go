// Telegram activity: отправка сообщения через Agent Caller (DEC-005).
// Agent Caller на :3000 — наш существующий Node.js-транспорт к Telegram Bot API.
package activities

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"time"
)

// TelegramActivity — клиент к Agent Caller для отправки в Telegram.
type TelegramActivity struct {
	BaseURL string // http://127.0.0.1:3000
	Timeout time.Duration
}

// NewTelegramActivity конструктор.
func NewTelegramActivity(baseURL string, timeout time.Duration) *TelegramActivity {
	return &TelegramActivity{BaseURL: baseURL, Timeout: timeout}
}

// SendMessageParams — параметры отправки.
type SendMessageParams struct {
	ChatID string
	Text   string
}

// SendMessage — отправить сообщение.
func (t *TelegramActivity) SendMessage(ctx context.Context, params SendMessageParams, opts CallOptions) error {
	body, err := json.Marshal(map[string]string{
		"chat_id": params.ChatID,
		"text":    params.Text,
	})
	if err != nil {
		return fmt.Errorf("marshal: %w", err)
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodPost,
		t.BaseURL+"/send-tg", bytes.NewReader(body))
	if err != nil {
		return fmt.Errorf("create request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	if opts.TraceID != "" {
		req.Header.Set("X-Trace-Id", opts.TraceID)
	}

	client := newHTTPClient(resolveTimeout(opts, t.Timeout))
	resp, err := client.Do(req)
	if err != nil {
		return fmt.Errorf("agent-caller: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		errBody, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("agent-caller returned %d: %s",
			resp.StatusCode, string(errBody))
	}
	return nil
}

// HealthCheck — для startup readiness.
func (t *TelegramActivity) HealthCheck(ctx context.Context) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, t.BaseURL+"/status", nil)
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
		return fmt.Errorf("agent-caller unhealthy: status=%d", resp.StatusCode)
	}
	return nil
}
