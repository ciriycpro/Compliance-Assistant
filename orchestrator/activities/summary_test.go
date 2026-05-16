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

func TestSummaryActivity_Summarize_Success(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodPost, r.Method)
		assert.Equal(t, "/summary", r.URL.Path)

		var req map[string]interface{}
		json.NewDecoder(r.Body).Decode(&req)
		assert.Equal(t, "2026-05-13", req["period"])

		json.NewEncoder(w).Encode(DigestResult{
			SummaryMarkdown: "## Привет, в почте за сегодня...",
			SummaryTelegram: "Привет, в почте за сегодня — 2 письма от Контур.Экстерн.",
			TokensIn:        3186,
			TokensOut:       832,
			CostUSD:         0.0049,
			Model:           "anthropic/claude-haiku-4.5",
			FallbackUsed:    false,
		})
	}))
	defer server.Close()

	summary := NewSummaryActivity(server.URL, 60*time.Second)
	result, err := summary.Summarize(context.Background(),
		SummarizeParams{
			Period:   "2026-05-13",
			Messages: []Message{{MessageID: "abc", Subject: "Test"}},
		},
		CallOptions{},
	)
	require.NoError(t, err)
	assert.Contains(t, result.SummaryTelegram, "Привет")
	assert.Equal(t, "anthropic/claude-haiku-4.5", result.Model)
	assert.Equal(t, 3186, result.TokensIn)
}
