// WhatsApp activity: отправка алерта через Agent Caller (POST /send-wa).
// Реализация Agent Caller через whatsapp-web.js: запускает headless Chrome,
// отправляет, убивает Chrome. Время: ~10 секунд на отправку.
// RAM peak: ~150 МБ во время отправки.
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

// WhatsAppActivity — клиент к Agent Caller для отправки в WhatsApp.
type WhatsAppActivity struct {
	BaseURL string
	Timeout time.Duration
}

// NewWhatsAppActivity конструктор.
func NewWhatsAppActivity(baseURL string, timeout time.Duration) *WhatsAppActivity {
	return &WhatsAppActivity{BaseURL: baseURL, Timeout: timeout}
}

// SendWAParams — параметры отправки.
type SendWAParams struct {
	Number string // номер в формате 7XXXXXXXXXX (без + и пробелов)
	Text   string
}

// SendMessage — отправить WA-сообщение.
// ВНИМАНИЕ: занимает ~10 сек на стороне Agent Caller (Chrome startup + send + destroy).
// Соответственно нужен timeout >= 30 секунд.
func (w *WhatsAppActivity) SendMessage(ctx context.Context, params SendWAParams, opts CallOptions) error {
	body, err := json.Marshal(map[string]string{
		"number": params.Number,
		"text":   params.Text,
	})
	if err != nil {
		return fmt.Errorf("marshal: %w", err)
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodPost,
		w.BaseURL+"/send-wa", bytes.NewReader(body))
	if err != nil {
		return fmt.Errorf("create request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	if opts.TraceID != "" {
		req.Header.Set("X-Trace-Id", opts.TraceID)
	}

	client := newHTTPClient(resolveTimeout(opts, w.Timeout))
	resp, err := client.Do(req)
	if err != nil {
		return fmt.Errorf("agent-caller wa: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		errBody, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("agent-caller wa returned %d: %s",
			resp.StatusCode, string(errBody))
	}
	return nil
}
