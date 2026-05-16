// Mail-service activity: GET /mail/since/<date>?limit=N
package activities

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"time"
)

// MailActivity — клиент к mail-service.
type MailActivity struct {
	BaseURL string
	Timeout time.Duration
}

// NewMailActivity конструктор.
func NewMailActivity(baseURL string, timeout time.Duration) *MailActivity {
	return &MailActivity{BaseURL: baseURL, Timeout: timeout}
}

// GetMailSinceParams — параметры для GetMailSince.
type GetMailSinceParams struct {
	Since string // ISO-date "2026-05-13" или "2026-05-13T09:00"
	Limit int    // 0 = без лимита
}

// GetMailSince — получить письма с указанной даты.
func (m *MailActivity) GetMailSince(ctx context.Context, params GetMailSinceParams, opts CallOptions) ([]Message, error) {
	endpoint, err := url.Parse(fmt.Sprintf("%s/mail/since/%s", m.BaseURL, params.Since))
	if err != nil {
		return nil, fmt.Errorf("parse url: %w", err)
	}
	if params.Limit > 0 {
		q := endpoint.Query()
		q.Set("limit", fmt.Sprintf("%d", params.Limit))
		endpoint.RawQuery = q.Encode()
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint.String(), nil)
	if err != nil {
		return nil, fmt.Errorf("create request: %w", err)
	}
	if opts.TraceID != "" {
		req.Header.Set("X-Trace-Id", opts.TraceID)
	}

	client := newHTTPClient(resolveTimeout(opts, m.Timeout))
	resp, err := client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("mail-service: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(resp.Body)
		return nil, fmt.Errorf("mail-service returned %d: %s", resp.StatusCode, string(body))
	}

	var messages []Message
	if err := json.NewDecoder(resp.Body).Decode(&messages); err != nil {
		return nil, fmt.Errorf("decode response: %w", err)
	}
	return messages, nil
}

// HealthCheck — для startup readiness.
func (m *MailActivity) HealthCheck(ctx context.Context) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, m.BaseURL+"/health", nil)
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
		return fmt.Errorf("mail-service unhealthy: status=%d", resp.StatusCode)
	}
	return nil
}
