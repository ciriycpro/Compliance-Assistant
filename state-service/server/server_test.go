package server

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/redis/go-redis/v9"

	"github.com/ciriycpro/mail-stack/state-service/logging"
	"github.com/ciriycpro/mail-stack/state-service/store"
)

const testAPIKey = "test-api-key-must-be-long-enough"

func newTestServer(t *testing.T) (*Server, *store.Store) {
	t.Helper()
	rdb := redis.NewClient(&redis.Options{
		Addr:        "127.0.0.1:6379",
		DB:          15,
		DialTimeout: 2 * time.Second,
	})
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	if err := rdb.Ping(ctx).Err(); err != nil {
		t.Skipf("Redis not available: %v", err)
	}
	_ = rdb.FlushDB(ctx).Err()

	st := &store.Store{}
	// Используем неэкспортируемое поле через рефлексию — обходной путь:
	// проще пересобрать через store.New с подменой Redis instance...
	// Решение: оставляем приватность, используем store.New с реальным Redis
	st = store.New("127.0.0.1:6379", "", 15, 2*time.Second)

	logger := logging.New("error") // тихие тесты
	srv := New(st, logger, testAPIKey, 60*time.Second)
	return srv, st
}

func doRequest(t *testing.T, srv *Server, method, path string, body any, withAuth bool) *httptest.ResponseRecorder {
	t.Helper()
	var bodyReader *bytes.Buffer
	if body != nil {
		b, _ := json.Marshal(body)
		bodyReader = bytes.NewBuffer(b)
	} else {
		bodyReader = bytes.NewBuffer(nil)
	}
	req := httptest.NewRequest(method, path, bodyReader)
	if withAuth {
		req.Header.Set("X-API-Key", testAPIKey)
	}
	rec := httptest.NewRecorder()
	srv.Handler().ServeHTTP(rec, req)
	return rec
}

func TestHealth(t *testing.T) {
	srv, _ := newTestServer(t)

	rec := doRequest(t, srv, "GET", "/health", nil, false)
	if rec.Code != http.StatusOK {
		t.Errorf("status = %d, want 200; body=%s", rec.Code, rec.Body.String())
	}
}

func TestAuth_NoKey(t *testing.T) {
	srv, _ := newTestServer(t)
	rec := doRequest(t, srv, "GET", "/state/123/last_at", nil, false)
	if rec.Code != http.StatusUnauthorized {
		t.Errorf("status = %d, want 401", rec.Code)
	}
}

func TestAuth_WrongKey(t *testing.T) {
	srv, _ := newTestServer(t)
	req := httptest.NewRequest("GET", "/state/123/last_at", nil)
	req.Header.Set("X-API-Key", "wrong-key")
	rec := httptest.NewRecorder()
	srv.Handler().ServeHTTP(rec, req)
	if rec.Code != http.StatusUnauthorized {
		t.Errorf("status = %d, want 401", rec.Code)
	}
}

func TestGetLastAt_NotFound(t *testing.T) {
	srv, _ := newTestServer(t)
	rec := doRequest(t, srv, "GET", "/state/777/last_at", nil, true)
	if rec.Code != http.StatusNotFound {
		t.Errorf("status = %d, want 404", rec.Code)
	}
}

func TestSetGetLastAt_RoundTrip(t *testing.T) {
	srv, _ := newTestServer(t)

	body := map[string]string{"timestamp": "2026-05-16T14:30:00Z"}
	rec := doRequest(t, srv, "POST", "/state/123/last_at", body, true)
	if rec.Code != http.StatusOK {
		t.Fatalf("POST status = %d, want 200; body=%s", rec.Code, rec.Body.String())
	}

	rec = doRequest(t, srv, "GET", "/state/123/last_at", nil, true)
	if rec.Code != http.StatusOK {
		t.Fatalf("GET status = %d, want 200", rec.Code)
	}
	var resp map[string]any
	_ = json.Unmarshal(rec.Body.Bytes(), &resp)
	if !strings.HasPrefix(resp["last_at"].(string), "2026-05-16T14:30:00") {
		t.Errorf("last_at = %v, want 2026-05-16T14:30:00...", resp["last_at"])
	}
}

func TestSetLastAt_EmptyBody_UsesNow(t *testing.T) {
	srv, _ := newTestServer(t)
	body := map[string]string{} // empty
	rec := doRequest(t, srv, "POST", "/state/456/last_at", body, true)
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200; body=%s", rec.Code, rec.Body.String())
	}
}

func TestSetLastAt_BadTimestamp(t *testing.T) {
	srv, _ := newTestServer(t)
	body := map[string]string{"timestamp": "not-a-date"}
	rec := doRequest(t, srv, "POST", "/state/1/last_at", body, true)
	if rec.Code != http.StatusBadRequest {
		t.Errorf("status = %d, want 400", rec.Code)
	}
}

func TestChatID_Invalid(t *testing.T) {
	srv, _ := newTestServer(t)
	rec := doRequest(t, srv, "GET", "/state/not-a-number/last_at", nil, true)
	if rec.Code != http.StatusBadRequest {
		t.Errorf("status = %d, want 400", rec.Code)
	}
}

func TestLock_Acquire(t *testing.T) {
	srv, _ := newTestServer(t)
	rec := doRequest(t, srv, "POST", "/state/111/lock", map[string]int{"ttl_seconds": 30}, true)
	if rec.Code != http.StatusOK {
		t.Errorf("status = %d, want 200; body=%s", rec.Code, rec.Body.String())
	}
}

func TestLock_AlreadyHeld(t *testing.T) {
	srv, _ := newTestServer(t)
	_ = doRequest(t, srv, "POST", "/state/222/lock", map[string]int{"ttl_seconds": 30}, true)

	rec := doRequest(t, srv, "POST", "/state/222/lock", map[string]int{"ttl_seconds": 30}, true)
	if rec.Code != http.StatusConflict {
		t.Errorf("status = %d, want 409", rec.Code)
	}
}

func TestLock_Release(t *testing.T) {
	srv, _ := newTestServer(t)
	_ = doRequest(t, srv, "POST", "/state/333/lock", map[string]int{"ttl_seconds": 30}, true)

	rec := doRequest(t, srv, "DELETE", "/state/333/lock", nil, true)
	if rec.Code != http.StatusOK {
		t.Errorf("status = %d, want 200", rec.Code)
	}

	// После release lock можно получить заново
	rec = doRequest(t, srv, "POST", "/state/333/lock", map[string]int{"ttl_seconds": 30}, true)
	if rec.Code != http.StatusOK {
		t.Errorf("after release: status = %d, want 200", rec.Code)
	}
}

func TestStatus_Empty(t *testing.T) {
	srv, _ := newTestServer(t)
	rec := doRequest(t, srv, "GET", "/state/888/status", nil, true)
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", rec.Code)
	}
	var resp map[string]any
	_ = json.Unmarshal(rec.Body.Bytes(), &resp)
	if resp["last_at"] != nil {
		t.Errorf("last_at should be nil for empty state, got %v", resp["last_at"])
	}
	if resp["locked"] != false {
		t.Errorf("locked should be false, got %v", resp["locked"])
	}
}

func TestStatus_WithData(t *testing.T) {
	srv, _ := newTestServer(t)
	_ = doRequest(t, srv, "POST", "/state/999/last_at", map[string]string{"timestamp": "2026-05-16T10:00:00Z"}, true)
	_ = doRequest(t, srv, "POST", "/state/999/lock", map[string]int{"ttl_seconds": 60}, true)

	rec := doRequest(t, srv, "GET", "/state/999/status", nil, true)
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", rec.Code)
	}
	var resp map[string]any
	_ = json.Unmarshal(rec.Body.Bytes(), &resp)
	if resp["last_at"] == nil {
		t.Error("last_at should not be nil")
	}
	if resp["locked"] != true {
		t.Errorf("locked should be true, got %v", resp["locked"])
	}
	if resp["lock_ttl_seconds"] == nil {
		t.Error("lock_ttl_seconds should be present when locked")
	}
}
