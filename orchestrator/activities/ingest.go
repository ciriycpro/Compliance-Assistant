// Compliance-logic ingest activity: POST /statements/ingest (multipart: file + meta).
// HTTPS с RootCAs из ca.crt + X-API-Key header.
package activities

import (
	"bytes"
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"fmt"
	"io"
	"mime/multipart"
	"net/http"
	"os"
	"path/filepath"
	"time"
)

// IngestResult — зеркало StatementIngestService.IngestResult (Java record).
type IngestResult struct {
	ClientInn       string `json:"clientInn"`
	StatementID     string `json:"statementId"` // UUID как string
	AccountNumber   string `json:"accountNumber"`
	OpsCreated      int    `json:"opsCreated"`
	OpsSkipped      int    `json:"opsSkipped"`
	GapsClosed      int    `json:"gapsClosed"`
	AlreadyIngested bool   `json:"alreadyIngested"`
}

// IngestActivity — клиент к compliance-logic /statements/ingest (HTTPS + X-API-Key).
// Используется собственный http.Client с custom TLS RootCAs — общий newHTTPClient
// (types.go) не поддерживает кастомный CA.
type IngestActivity struct {
	BaseURL string
	APIKey  string
	Timeout time.Duration
	client  *http.Client
}

// NewIngestActivity — eager init: читает CA.crt и валидирует PEM при старте.
// Возвращает err если CA недоступен — fail-fast при старте orchestrator.
func NewIngestActivity(baseURL, apiKey, caCertPath string, timeout time.Duration) (*IngestActivity, error) {
	caBytes, err := os.ReadFile(caCertPath)
	if err != nil {
		return nil, fmt.Errorf("read CA cert %s: %w", caCertPath, err)
	}
	pool := x509.NewCertPool()
	if !pool.AppendCertsFromPEM(caBytes) {
		return nil, fmt.Errorf("append CA cert from %s to pool: invalid PEM", caCertPath)
	}
	return &IngestActivity{
		BaseURL: baseURL,
		APIKey:  apiKey,
		Timeout: timeout,
		client: &http.Client{
			Timeout: timeout,
			Transport: &http.Transport{
				TLSClientConfig: &tls.Config{RootCAs: pool},
			},
		},
	}, nil
}

// Ingest — POST /statements/ingest (multipart: file + meta).
// Файл читается в память целиком (выписки ≤25 MB по Spring multipart limit, реально 0.1–2 MB).
func (i *IngestActivity) Ingest(ctx context.Context, filePath, metaJSON string, opts CallOptions) (*IngestResult, error) {
	fileBytes, err := os.ReadFile(filePath)
	if err != nil {
		return nil, fmt.Errorf("read file %s: %w", filePath, err)
	}

	var body bytes.Buffer
	mw := multipart.NewWriter(&body)

	fw, err := mw.CreateFormFile("file", filepath.Base(filePath))
	if err != nil {
		return nil, fmt.Errorf("create form file: %w", err)
	}
	if _, err := fw.Write(fileBytes); err != nil {
		return nil, fmt.Errorf("write file bytes: %w", err)
	}
	if err := mw.WriteField("meta", metaJSON); err != nil {
		return nil, fmt.Errorf("write meta field: %w", err)
	}
	if err := mw.Close(); err != nil {
		return nil, fmt.Errorf("close multipart writer: %w", err)
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, i.BaseURL+"/statements/ingest", &body)
	if err != nil {
		return nil, fmt.Errorf("create request: %w", err)
	}
	req.Header.Set("Content-Type", mw.FormDataContentType())
	req.Header.Set("X-API-Key", i.APIKey)
	if opts.TraceID != "" {
		req.Header.Set("X-Trace-Id", opts.TraceID)
	}

	// resolveTimeout через свой client — общий newHTTPClient не подходит из-за TLS.
	timeout := resolveTimeout(opts, i.Timeout)
	client := i.client
	if timeout != i.Timeout {
		client = &http.Client{Timeout: timeout, Transport: i.client.Transport}
	}

	resp, err := client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("compliance-logic ingest: %w", err)
	}
	defer resp.Body.Close()

	respBody, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("compliance-logic returned %d: %s", resp.StatusCode, string(respBody))
	}

	var result IngestResult
	if err := json.Unmarshal(respBody, &result); err != nil {
		return nil, fmt.Errorf("decode response: %w", err)
	}
	return &result, nil
}

// HealthCheck — GET /actuator/health для startup readiness.
func (i *IngestActivity) HealthCheck(ctx context.Context) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, i.BaseURL+"/actuator/health", nil)
	if err != nil {
		return err
	}
	req.Header.Set("X-API-Key", i.APIKey)

	client := &http.Client{Timeout: 5 * time.Second, Transport: i.client.Transport}
	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("compliance-logic unhealthy: status=%d", resp.StatusCode)
	}
	return nil
}
