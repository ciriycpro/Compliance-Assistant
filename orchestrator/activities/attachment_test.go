package activities

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestAttachmentActivity_Download_Success(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodPost, r.Method)
		assert.Equal(t, "/download", r.URL.Path)
		var req map[string]string
		json.NewDecoder(r.Body).Decode(&req)
		assert.Equal(t, "msg-123", req["messageId"])
		assert.Equal(t, "договор.pdf", req["filename"])

		json.NewEncoder(w).Encode(DownloadResult{
			Path:   "/var/lib/mail-stack/attachments/msg-123/dogovor.pdf",
			SHA256: "abc123",
			Size:   1024,
			MIME:   "application/pdf",
		})
	}))
	defer server.Close()

	att := NewAttachmentActivity(server.URL, 5*time.Second)
	result, err := att.Download(context.Background(),
		DownloadParams{MessageID: "msg-123", Filename: "договор.pdf"},
		CallOptions{},
	)
	require.NoError(t, err)
	assert.Equal(t, "/var/lib/mail-stack/attachments/msg-123/dogovor.pdf", result.Path)
	assert.Equal(t, int64(1024), result.Size)
}

func TestAttachmentActivity_PathTraversal_Refused(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		json.NewEncoder(w).Encode(DownloadResult{Path: "/etc/passwd"})
	}))
	defer server.Close()

	att := NewAttachmentActivity(server.URL, 5*time.Second)
	_, err := att.Download(context.Background(),
		DownloadParams{MessageID: "msg-123", Filename: "evil.pdf"},
		CallOptions{},
	)
	require.Error(t, err)
	assert.Contains(t, err.Error(), "suspicious path")
}

func TestAttachmentActivity_PathDoubleDot_Refused(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		json.NewEncoder(w).Encode(DownloadResult{
			Path: "/var/lib/mail-stack/attachments/../../etc/passwd",
		})
	}))
	defer server.Close()

	att := NewAttachmentActivity(server.URL, 5*time.Second)
	_, err := att.Download(context.Background(),
		DownloadParams{MessageID: "msg-123", Filename: "evil.pdf"},
		CallOptions{},
	)
	require.Error(t, err)
	assert.Contains(t, err.Error(), "suspicious path")
}

func TestIsAllowedAttachmentPath(t *testing.T) {
	tests := []struct {
		path    string
		allowed bool
	}{
		{"/var/lib/mail-stack/attachments/abc/file.pdf", true},
		{"/tmp/test.jpg", true},
		{"/etc/passwd", false},
		{"/var/lib/mail-stack/attachments/../../etc/passwd", false},
		{"/home/iakshin77/.ssh/id_rsa", false},
		{"", false},
	}
	for _, tt := range tests {
		t.Run(tt.path, func(t *testing.T) {
			assert.Equal(t, tt.allowed, isAllowedAttachmentPath(tt.path))
		})
	}
}
