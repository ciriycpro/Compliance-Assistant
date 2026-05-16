// Summary-service activity: POST /summary {period, messages}
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

// SummaryActivity — клиент к summary-service.
type SummaryActivity struct {
	BaseURL string
	Timeout time.Duration
}

// NewSummaryActivity конструктор.
func NewSummaryActivity(baseURL string, timeout time.Duration) *SummaryActivity {
	return &SummaryActivity{BaseURL: baseURL, Timeout: timeout}
}

// SummarizeParams — параметры суммирования.
type SummarizeParams struct {
	Period   string    // "2026-05-13" или "2026-05-12 to 2026-05-13"
	Messages []Message // массив с parsed_attachments
}

// Summarize — получить дайджест от summary-service.
func (s *SummaryActivity) Summarize(ctx context.Context, params SummarizeParams, opts CallOptions) (*DigestResult, error) {
	payload := map[string]interface{}{
		"period":   params.Period,
		"messages": params.Messages,
	}
	body, err := json.Marshal(payload)
	if err != nil {
		return nil, fmt.Errorf("marshal: %w", err)
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodPost,
		s.BaseURL+"/summary", bytes.NewReader(body))
	if err != nil {
		return nil, fmt.Errorf("create request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	if opts.TraceID != "" {
		req.Header.Set("X-Trace-Id", opts.TraceID)
	}

	client := newHTTPClient(resolveTimeout(opts, s.Timeout))
	resp, err := client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("summary-service: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		errBody, _ := io.ReadAll(resp.Body)
		return nil, fmt.Errorf("summary-service returned %d: %s",
			resp.StatusCode, string(errBody))
	}

	var result DigestResult
	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		return nil, fmt.Errorf("decode response: %w", err)
	}
	return &result, nil
}

// HealthCheck — для startup readiness.
func (s *SummaryActivity) HealthCheck(ctx context.Context) error {
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
		return fmt.Errorf("summary-service unhealthy: status=%d", resp.StatusCode)
	}
	return nil
}
