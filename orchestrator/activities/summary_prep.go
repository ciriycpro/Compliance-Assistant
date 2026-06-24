// Summary-prep activity: POST /distill {text, metadata, quality_mode, contract_strictness}
// DEC-0030: дистилляция длинных документов через Claude Haiku 4.5 via OpenRouter.
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

// SummaryPrepActivity — клиент к summary-prep сервису.
type SummaryPrepActivity struct {
	BaseURL string
	APIKey  string
	Timeout time.Duration
}

// NewSummaryPrepActivity конструктор.
func NewSummaryPrepActivity(baseURL, apiKey string, timeout time.Duration) *SummaryPrepActivity {
	return &SummaryPrepActivity{
		BaseURL: baseURL,
		APIKey:  apiKey,
		Timeout: timeout,
	}
}

// DistillParams — параметры дистилляции одного документа.
type DistillParams struct {
	Text     string
	From     string
	Subject  string
	Date     string
	Filename string

	// QualityMode: "fast" (для дайджеста, дёшево) | "deep" (для compliance, дорого).
	// Default: "fast".
	QualityMode string

	// ContractStrictness: "soft" (для саммари) | "hard" (для compliance с raw_text).
	// Default: "soft".
	ContractStrictness string
}

// DistillResultRaw — возвращаемый JSON. Не разбираем поля в struct — отдаём
// summary-service как map[string]any. Схема живёт в summary-prep/schemas.py.
type DistillResultRaw = map[string]any

// Distill — дистилляция документа через summary-prep:8772/distill.
func (s *SummaryPrepActivity) Distill(ctx context.Context, params DistillParams, opts CallOptions) (DistillResultRaw, error) {
	qmode := params.QualityMode
	if qmode == "" {
		qmode = "fast"
	}
	strictness := params.ContractStrictness
	if strictness == "" {
		strictness = "soft"
	}

	payload := map[string]any{
		"text": params.Text,
		"metadata": map[string]any{
			"from":                params.From,
			"subject":             params.Subject,
			"date":                params.Date,
			"attachment_filename": params.Filename,
		},
		"quality_mode":        qmode,
		"contract_strictness": strictness,
	}

	body, err := json.Marshal(payload)
	if err != nil {
		return nil, fmt.Errorf("marshal: %w", err)
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodPost,
		s.BaseURL+"/distill", bytes.NewReader(body))
	if err != nil {
		return nil, fmt.Errorf("create request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	if s.APIKey != "" {
		req.Header.Set("X-API-Key", s.APIKey)
	}
	if opts.TraceID != "" {
		req.Header.Set("X-Trace-Id", opts.TraceID)
	}

	client := newHTTPClient(resolveTimeout(opts, s.Timeout))
	resp, err := client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("summary-prep: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		errBody, _ := io.ReadAll(resp.Body)
		return nil, fmt.Errorf("summary-prep returned %d: %s",
			resp.StatusCode, string(errBody))
	}

	var result DistillResultRaw
	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		return nil, fmt.Errorf("decode response: %w", err)
	}
	return result, nil
}

// HealthCheck — для startup readiness.
func (s *SummaryPrepActivity) HealthCheck(ctx context.Context) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, s.BaseURL+"/health", nil)
	if err != nil {
		return err
	}
	client := newHTTPClient(5 * time.Second)
	resp, err := client.Do(req)
	if err != nil {
		return fmt.Errorf("summary-prep health: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("summary-prep health: status %d", resp.StatusCode)
	}
	return nil
}
