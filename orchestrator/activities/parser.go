// Parser-service activity: POST /parse {path}
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

// ParserActivity — клиент к parser-service.
type ParserActivity struct {
	BaseURL string
	Timeout time.Duration
}

// NewParserActivity конструктор.
func NewParserActivity(baseURL string, timeout time.Duration) *ParserActivity {
	return &ParserActivity{BaseURL: baseURL, Timeout: timeout}
}

// ParseParams — параметры парсинга.
type ParseParams struct {
	Path string // путь к файлу на FS attachment-service
}

// ParseResult — результат парсинга.
type ParseResult struct {
	Text     string   `json:"text"`
	Method   string   `json:"method"`
	Format   string   `json:"format"`
	Warnings []string `json:"warnings,omitempty"`
	CostUSD  float64  `json:"cost_usd,omitempty"`
}

// Parse — распарсить файл через parser-service.
func (p *ParserActivity) Parse(ctx context.Context, params ParseParams, opts CallOptions) (*ParseResult, error) {
	// Secure-by-Design Уровень 0: дополнительная проверка path traversal
	if !isAllowedAttachmentPath(params.Path) {
		return nil, fmt.Errorf("refuse to parse suspicious path: %s", params.Path)
	}

	body, err := json.Marshal(map[string]string{"path": params.Path})
	if err != nil {
		return nil, fmt.Errorf("marshal: %w", err)
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodPost,
		p.BaseURL+"/parse", bytes.NewReader(body))
	if err != nil {
		return nil, fmt.Errorf("create request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	if opts.TraceID != "" {
		req.Header.Set("X-Trace-Id", opts.TraceID)
	}

	client := newHTTPClient(resolveTimeout(opts, p.Timeout))
	resp, err := client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("parser-service: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		errBody, _ := io.ReadAll(resp.Body)
		return nil, fmt.Errorf("parser-service returned %d: %s",
			resp.StatusCode, string(errBody))
	}

	var result ParseResult
	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		return nil, fmt.Errorf("decode response: %w", err)
	}
	return &result, nil
}

// HealthCheck — для startup readiness.
func (p *ParserActivity) HealthCheck(ctx context.Context) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, p.BaseURL+"/health", nil)
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
		return fmt.Errorf("parser-service unhealthy: status=%d", resp.StatusCode)
	}
	return nil
}


// ParseStatementParams — параметры детерминированного парса банковской выписки.
type ParseStatementParams struct {
	Path string
}

// ParsedStatement — один statement в распарсенной выписке.
// В одном файле может быть несколько statements (несколько счетов в одном PDF).
// Operations не разбираем — пробрасываем как []json.RawMessage в meta для compliance-logic.
type ParsedStatement struct {
	Account     string            `json:"account"`
	PeriodStart string            `json:"period_start"`
	PeriodEnd   string            `json:"period_end"`
	OwnerName   string            `json:"owner_name,omitempty"`
	Operations  []json.RawMessage `json:"operations"`
}

// ParseStatementResult — ответ /parse-statement.
type ParseStatementResult struct {
	Bank       string            `json:"bank"`
	Statements []ParsedStatement `json:"statements"`
}

// ParseStatement — POST /parse-statement (детерминированный парс банковской выписки).
// Сейчас поддерживается ВТБ (pdf+xlsx) и Альфа (pdf). На неизвестный банк/неподходящий
// файл parser-service вернёт 422 — это нормальный путь для нестатементных вложений
// (договоры, акты, скриншоты), workflow эту ошибку проглатывает и идёт дальше.
func (p *ParserActivity) ParseStatement(ctx context.Context, params ParseStatementParams, opts CallOptions) (*ParseStatementResult, error) {
	body, err := json.Marshal(map[string]string{"path": params.Path})
	if err != nil {
		return nil, fmt.Errorf("marshal: %w", err)
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodPost,
		p.BaseURL+"/parse-statement", bytes.NewReader(body))
	if err != nil {
		return nil, fmt.Errorf("create request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	if opts.TraceID != "" {
		req.Header.Set("X-Trace-Id", opts.TraceID)
	}

	client := newHTTPClient(resolveTimeout(opts, p.Timeout))
	resp, err := client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("parser-service: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		errBody, _ := io.ReadAll(resp.Body)
		return nil, fmt.Errorf("parser-service /parse-statement returned %d: %s",
			resp.StatusCode, string(errBody))
	}

	var result ParseStatementResult
	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		return nil, fmt.Errorf("decode response: %w", err)
	}
	return &result, nil
}
