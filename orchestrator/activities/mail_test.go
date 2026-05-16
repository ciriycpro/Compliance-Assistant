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

func TestMailActivity_GetMailSince_Success(t *testing.T) {
	expected := []Message{
		{
			MessageID: "abc-123",
			From:      "Контур.Экстерн <robot@kontur-extern.ru>",
			Subject:   "Решение о выездной проверке",
			Date:      "2026-05-12T14:13:00",
			BodyText:  "ФНС 7710 уведомляет...",
		},
	}

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodGet, r.Method)
		assert.Equal(t, "/mail/since/2026-05-12", r.URL.Path)
		assert.Equal(t, "test-trace-123", r.Header.Get("X-Trace-Id"))
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(expected)
	}))
	defer server.Close()

	mail := NewMailActivity(server.URL, 5*time.Second)
	result, err := mail.GetMailSince(context.Background(),
		GetMailSinceParams{Since: "2026-05-12"},
		CallOptions{TraceID: "test-trace-123"},
	)

	require.NoError(t, err)
	require.Len(t, result, 1)
	assert.Equal(t, "abc-123", result[0].MessageID)
	assert.Equal(t, "Решение о выездной проверке", result[0].Subject)
}

func TestMailActivity_GetMailSince_WithLimit(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, "50", r.URL.Query().Get("limit"))
		json.NewEncoder(w).Encode([]Message{})
	}))
	defer server.Close()

	mail := NewMailActivity(server.URL, 5*time.Second)
	_, err := mail.GetMailSince(context.Background(),
		GetMailSinceParams{Since: "2026-05-12", Limit: 50},
		CallOptions{},
	)
	require.NoError(t, err)
}

func TestMailActivity_GetMailSince_ServerError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
		w.Write([]byte(`{"error": "IMAP timeout"}`))
	}))
	defer server.Close()

	mail := NewMailActivity(server.URL, 5*time.Second)
	_, err := mail.GetMailSince(context.Background(),
		GetMailSinceParams{Since: "2026-05-12"},
		CallOptions{},
	)
	require.Error(t, err)
	assert.Contains(t, err.Error(), "500")
}

func TestMailActivity_HealthCheck(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, "/health", r.URL.Path)
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()

	mail := NewMailActivity(server.URL, 5*time.Second)
	err := mail.HealthCheck(context.Background())
	require.NoError(t, err)
}
