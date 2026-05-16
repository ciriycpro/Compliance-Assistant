// Attachment-service activity: POST /download {messageId, filename}
package activities

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

// AttachmentActivity — клиент к attachment-service.
type AttachmentActivity struct {
	BaseURL string
	Timeout time.Duration
}

// NewAttachmentActivity конструктор.
func NewAttachmentActivity(baseURL string, timeout time.Duration) *AttachmentActivity {
	return &AttachmentActivity{BaseURL: baseURL, Timeout: timeout}
}

// DownloadParams — параметры скачивания.
type DownloadParams struct {
	MessageID string
	Filename  string
}

// DownloadResult — результат скачивания.
type DownloadResult struct {
	Path     string `json:"path"`
	SHA256   string `json:"sha256"`
	Size     int64  `json:"size"`
	MIME     string `json:"mime,omitempty"`
	Cached   bool   `json:"cached,omitempty"`
}

// Download — скачать вложение из IMAP и сохранить в FS attachment-service.
func (a *AttachmentActivity) Download(ctx context.Context, params DownloadParams, opts CallOptions) (*DownloadResult, error) {
	body, err := json.Marshal(map[string]string{
		"messageId": params.MessageID,
		"filename":  params.Filename,
	})
	if err != nil {
		return nil, fmt.Errorf("marshal: %w", err)
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodPost,
		a.BaseURL+"/download", bytes.NewReader(body))
	if err != nil {
		return nil, fmt.Errorf("create request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	if opts.TraceID != "" {
		req.Header.Set("X-Trace-Id", opts.TraceID)
	}

	client := newHTTPClient(resolveTimeout(opts, a.Timeout))
	resp, err := client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("attachment-service: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		errBody, _ := io.ReadAll(resp.Body)
		return nil, fmt.Errorf("attachment-service returned %d: %s",
			resp.StatusCode, string(errBody))
	}

	var result DownloadResult
	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		return nil, fmt.Errorf("decode response: %w", err)
	}

	// Secure-by-Design Уровень 0: path validation
	// path должен начинаться с /var/lib/mail-stack/attachments/ или /tmp/
	// и не содержать ".." (path traversal)
	if !isAllowedAttachmentPath(result.Path) {
		return nil, fmt.Errorf("attachment-service returned suspicious path: %s", result.Path)
	}
	return &result, nil
}

// isAllowedAttachmentPath — проверка path traversal (DEC-017 Уровень 0).
// Path должен начинаться с разрешённого префикса и не содержать ".."
func isAllowedAttachmentPath(path string) bool {
	allowedPrefixes := []string{
		"/var/lib/mail-stack/attachments/",
		"/tmp/",
	}
	matched := false
	for _, prefix := range allowedPrefixes {
		if strings.HasPrefix(path, prefix) {
			matched = true
			break
		}
	}
	if !matched {
		return false
	}
	if strings.Contains(path, "..") {
		return false
	}
	return true
}

// HealthCheck — для startup readiness.
func (a *AttachmentActivity) HealthCheck(ctx context.Context) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, a.BaseURL+"/health", nil)
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
		return fmt.Errorf("attachment-service unhealthy: status=%d", resp.StatusCode)
	}
	return nil
}
