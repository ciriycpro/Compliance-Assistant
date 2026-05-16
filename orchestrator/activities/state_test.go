package activities

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

const testStateAPIKey = "test-state-api-key"

func newStateTestServer(t *testing.T, handler http.HandlerFunc) *httptest.Server {
	t.Helper()
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		got := r.Header.Get("X-API-Key")
		if got != testStateAPIKey {
			w.WriteHeader(http.StatusUnauthorized)
			_, _ = w.Write([]byte(`{"error":"unauthorized"}`))
			return
		}
		handler(w, r)
	}))
}

func TestStateActivity_GetLastAt_Success(t *testing.T) {
	srv := newStateTestServer(t, func(w http.ResponseWriter, r *http.Request) {
		if !strings.Contains(r.URL.Path, "/state/123/last_at") {
			t.Errorf("unexpected path: %s", r.URL.Path)
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"chat_id":123,"last_at":"2026-05-16T10:00:00Z"}`))
	})
	defer srv.Close()

	act := NewStateActivity(srv.URL, testStateAPIKey, 5*time.Second)
	got, err := act.GetLastAt(context.Background(), GetLastAtParams{ChatID: 123}, CallOptions{TraceID: "test-trace"})
	if err != nil {
		t.Fatalf("GetLastAt: %v", err)
	}
	want := time.Date(2026, 5, 16, 10, 0, 0, 0, time.UTC)
	if !got.Equal(want) {
		t.Errorf("got %v, want %v", got, want)
	}
}

func TestStateActivity_GetLastAt_NotFound(t *testing.T) {
	srv := newStateTestServer(t, func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNotFound)
		_, _ = w.Write([]byte(`{"error":"not_found"}`))
	})
	defer srv.Close()

	act := NewStateActivity(srv.URL, testStateAPIKey, 5*time.Second)
	_, err := act.GetLastAt(context.Background(), GetLastAtParams{ChatID: 999}, CallOptions{})
	if !errors.Is(err, ErrStateNotFound) {
		t.Errorf("expected ErrStateNotFound, got %v", err)
	}
}

func TestStateActivity_GetLastAt_Unauthorized(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusUnauthorized)
	}))
	defer srv.Close()

	act := NewStateActivity(srv.URL, "wrong-key", 5*time.Second)
	_, err := act.GetLastAt(context.Background(), GetLastAtParams{ChatID: 1}, CallOptions{})
	if err == nil {
		t.Error("expected error on 401, got nil")
	}
}

func TestStateActivity_SetLastAt_WithTimestamp(t *testing.T) {
	srv := newStateTestServer(t, func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			t.Errorf("method = %s, want POST", r.Method)
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"chat_id":456,"last_at":"2026-05-16T20:00:00Z"}`))
	})
	defer srv.Close()

	act := NewStateActivity(srv.URL, testStateAPIKey, 5*time.Second)
	ts := time.Date(2026, 5, 16, 20, 0, 0, 0, time.UTC)
	err := act.SetLastAt(context.Background(), SetLastAtParams{
		ChatID:    456,
		Timestamp: ts,
	}, CallOptions{TraceID: "trace-set"})
	if err != nil {
		t.Errorf("SetLastAt: %v", err)
	}
}

func TestStateActivity_SetLastAt_WithoutTimestamp(t *testing.T) {
	// Если Timestamp.IsZero — state-service должен поставить now()
	srv := newStateTestServer(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"chat_id":1,"last_at":"2026-05-16T20:00:00Z"}`))
	})
	defer srv.Close()

	act := NewStateActivity(srv.URL, testStateAPIKey, 5*time.Second)
	err := act.SetLastAt(context.Background(), SetLastAtParams{ChatID: 1}, CallOptions{})
	if err != nil {
		t.Errorf("SetLastAt without timestamp: %v", err)
	}
}

func TestStateActivity_AcquireLock_Success(t *testing.T) {
	srv := newStateTestServer(t, func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			t.Errorf("method = %s, want POST", r.Method)
		}
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"chat_id":1,"acquired":true,"ttl_seconds":30}`))
	})
	defer srv.Close()

	act := NewStateActivity(srv.URL, testStateAPIKey, 5*time.Second)
	err := act.AcquireLock(context.Background(), AcquireLockParams{
		ChatID:     1,
		TTLSeconds: 30,
	}, CallOptions{})
	if err != nil {
		t.Errorf("AcquireLock: %v", err)
	}
}

func TestStateActivity_AcquireLock_Conflict(t *testing.T) {
	srv := newStateTestServer(t, func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusConflict)
		_, _ = w.Write([]byte(`{"error":"lock_held"}`))
	})
	defer srv.Close()

	act := NewStateActivity(srv.URL, testStateAPIKey, 5*time.Second)
	err := act.AcquireLock(context.Background(), AcquireLockParams{ChatID: 1, TTLSeconds: 30}, CallOptions{})
	if !errors.Is(err, ErrLockHeld) {
		t.Errorf("expected ErrLockHeld, got %v", err)
	}
}

func TestStateActivity_ReleaseLock(t *testing.T) {
	srv := newStateTestServer(t, func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodDelete {
			t.Errorf("method = %s, want DELETE", r.Method)
		}
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"chat_id":1,"released":true}`))
	})
	defer srv.Close()

	act := NewStateActivity(srv.URL, testStateAPIKey, 5*time.Second)
	err := act.ReleaseLock(context.Background(), 1, CallOptions{})
	if err != nil {
		t.Errorf("ReleaseLock: %v", err)
	}
}

func TestStateActivity_HealthCheck(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/health" {
			t.Errorf("path = %s, want /health", r.URL.Path)
		}
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	act := NewStateActivity(srv.URL, testStateAPIKey, 5*time.Second)
	if err := act.HealthCheck(context.Background()); err != nil {
		t.Errorf("HealthCheck: %v", err)
	}
}

func TestStateActivity_HealthCheck_Fail(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusServiceUnavailable)
	}))
	defer srv.Close()

	act := NewStateActivity(srv.URL, testStateAPIKey, 5*time.Second)
	if err := act.HealthCheck(context.Background()); err == nil {
		t.Error("expected error on 503, got nil")
	}
}

func TestParseChatID(t *testing.T) {
	cases := []struct {
		in      string
		want    int64
		wantErr bool
	}{
		{"249979054", 249979054, false},
		{"-100123", -100123, false}, // супергруппы Telegram имеют отрицательные ID
		{"abc", 0, true},
		{"", 0, true},
	}
	for _, tc := range cases {
		got, err := ParseChatID(tc.in)
		if (err != nil) != tc.wantErr {
			t.Errorf("ParseChatID(%q): err=%v wantErr=%v", tc.in, err, tc.wantErr)
			continue
		}
		if !tc.wantErr && got != tc.want {
			t.Errorf("ParseChatID(%q) = %d, want %d", tc.in, got, tc.want)
		}
	}
}
