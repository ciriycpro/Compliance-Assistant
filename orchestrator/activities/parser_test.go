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

func TestParserActivity_Parse_Success(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodPost, r.Method)
		assert.Equal(t, "/parse", r.URL.Path)
		var req map[string]string
		json.NewDecoder(r.Body).Decode(&req)
		assert.Equal(t, "/var/lib/mail-stack/attachments/abc/file.pdf", req["path"])

		json.NewEncoder(w).Encode(ParseResult{
			Text:    "Договор подряда №247 на 45000 руб...",
			Method:  "pymupdf",
			Format:  "pdf-text",
			CostUSD: 0,
		})
	}))
	defer server.Close()

	parser := NewParserActivity(server.URL, 30*time.Second)
	result, err := parser.Parse(context.Background(),
		ParseParams{Path: "/var/lib/mail-stack/attachments/abc/file.pdf"},
		CallOptions{},
	)
	require.NoError(t, err)
	assert.Contains(t, result.Text, "Договор")
	assert.Equal(t, "pymupdf", result.Method)
}

func TestParserActivity_RefuseBadPath(t *testing.T) {
	called := false
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		called = true
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()

	parser := NewParserActivity(server.URL, 30*time.Second)
	_, err := parser.Parse(context.Background(),
		ParseParams{Path: "/etc/passwd"},
		CallOptions{},
	)
	require.Error(t, err)
	assert.False(t, called, "parser-service не должен был быть вызван для suspicious path")
	assert.Contains(t, err.Error(), "suspicious")
}
