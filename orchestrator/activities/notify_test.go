package activities

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestNotifyActivity_WorkflowDone_Success(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			t.Errorf("method = %s, want POST", r.Method)
		}
		if !strings.Contains(r.URL.Path, "/workflow-done") {
			t.Errorf("path = %s", r.URL.Path)
		}
		body, _ := io.ReadAll(r.Body)
		var got map[string]string
		_ = json.Unmarshal(body, &got)
		if got["chat_id"] != "249979054" {
			t.Errorf("chat_id = %q, want 249979054", got["chat_id"])
		}
		if got["status"] != "delivered" {
			t.Errorf("status = %q, want delivered", got["status"])
		}
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"ok":true}`))
	}))
	defer srv.Close()

	act := NewNotifyActivity(srv.URL, 5*time.Second)
	err := act.WorkflowDone(context.Background(), WorkflowDoneParams{
		ChatID:  "249979054",
		TraceID: "test-trace",
		Status:  "delivered",
	}, CallOptions{TraceID: "test-trace"})
	if err != nil {
		t.Errorf("WorkflowDone: %v", err)
	}
}

func TestNotifyActivity_WorkflowDone_AgentCallerDown(t *testing.T) {
	// Agent Caller недоступен (порт закрыт)
	act := NewNotifyActivity("http://127.0.0.1:1", 1*time.Second)
	err := act.WorkflowDone(context.Background(), WorkflowDoneParams{
		ChatID: "123",
		Status: "delivered",
	}, CallOptions{})
	if err == nil {
		t.Error("expected error when agent-caller is down")
	}
}

func TestNotifyActivity_WorkflowDone_500Error(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
		_, _ = w.Write([]byte(`{"error":"server error"}`))
	}))
	defer srv.Close()

	act := NewNotifyActivity(srv.URL, 5*time.Second)
	err := act.WorkflowDone(context.Background(), WorkflowDoneParams{
		ChatID: "123",
		Status: "delivered",
	}, CallOptions{})
	if err == nil {
		t.Error("expected error on 500")
	}
}

func TestNotifyActivity_WorkflowDone_StatusTypes(t *testing.T) {
	// Проверяем что разные status проходят без модификации
	for _, status := range []string{"delivered", "no_messages", "failed"} {
		t.Run(status, func(t *testing.T) {
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				body, _ := io.ReadAll(r.Body)
				var got map[string]string
				_ = json.Unmarshal(body, &got)
				if got["status"] != status {
					t.Errorf("status = %q, want %q", got["status"], status)
				}
				w.WriteHeader(http.StatusOK)
			}))
			defer srv.Close()

			act := NewNotifyActivity(srv.URL, 5*time.Second)
			err := act.WorkflowDone(context.Background(), WorkflowDoneParams{
				ChatID: "1",
				Status: status,
			}, CallOptions{})
			if err != nil {
				t.Errorf("status=%s: %v", status, err)
			}
		})
	}
}

// ============================================================
// WorkflowProgress тесты
// ============================================================

func TestNotifyActivity_WorkflowProgress_AttachmentsStart(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			t.Errorf("method = %s, want POST", r.Method)
		}
		if !strings.Contains(r.URL.Path, "/workflow-progress") {
			t.Errorf("path = %s, want /workflow-progress", r.URL.Path)
		}
		body, _ := io.ReadAll(r.Body)
		var got map[string]any
		_ = json.Unmarshal(body, &got)
		if got["chat_id"] != "249979054" {
			t.Errorf("chat_id = %v", got["chat_id"])
		}
		if got["step"] != "attachments_start" {
			t.Errorf("step = %v, want attachments_start", got["step"])
		}
		meta, ok := got["meta"].(map[string]any)
		if !ok {
			t.Fatalf("meta missing or wrong type: %T", got["meta"])
		}
		// JSON numbers распаковываются как float64
		if meta["count"] != float64(9) {
			t.Errorf("meta.count = %v, want 9", meta["count"])
		}
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"ok":true}`))
	}))
	defer srv.Close()

	act := NewNotifyActivity(srv.URL, 5*time.Second)
	err := act.WorkflowProgress(context.Background(), WorkflowProgressParams{
		ChatID:  "249979054",
		TraceID: "trace-1",
		Step:    "attachments_start",
		Meta:    map[string]any{"count": 9},
	}, CallOptions{TraceID: "trace-1"})
	if err != nil {
		t.Errorf("WorkflowProgress: %v", err)
	}
}

func TestNotifyActivity_WorkflowProgress_SummaryStart(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		var got map[string]any
		_ = json.Unmarshal(body, &got)
		if got["step"] != "summary_start" {
			t.Errorf("step = %v, want summary_start", got["step"])
		}
		meta := got["meta"].(map[string]any)
		if meta["elapsed_ms"] != float64(120000) {
			t.Errorf("elapsed_ms = %v, want 120000", meta["elapsed_ms"])
		}
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	act := NewNotifyActivity(srv.URL, 5*time.Second)
	err := act.WorkflowProgress(context.Background(), WorkflowProgressParams{
		ChatID: "123",
		Step:   "summary_start",
		Meta:   map[string]any{"elapsed_ms": 120000},
	}, CallOptions{})
	if err != nil {
		t.Errorf("WorkflowProgress: %v", err)
	}
}

func TestNotifyActivity_WorkflowProgress_NoMeta(t *testing.T) {
	// Meta может быть nil — тогда в JSON не должно быть ключа "meta"
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		var got map[string]any
		_ = json.Unmarshal(body, &got)
		if _, ok := got["meta"]; ok {
			t.Errorf("meta should be absent when Meta=nil, got: %v", got["meta"])
		}
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	act := NewNotifyActivity(srv.URL, 5*time.Second)
	err := act.WorkflowProgress(context.Background(), WorkflowProgressParams{
		ChatID: "1",
		Step:   "anything",
	}, CallOptions{})
	if err != nil {
		t.Errorf("WorkflowProgress: %v", err)
	}
}

func TestNotifyActivity_WorkflowProgress_AgentCallerDown(t *testing.T) {
	act := NewNotifyActivity("http://127.0.0.1:1", 1*time.Second)
	err := act.WorkflowProgress(context.Background(), WorkflowProgressParams{
		ChatID: "1",
		Step:   "test",
	}, CallOptions{})
	if err == nil {
		t.Error("expected error when agent-caller down")
	}
}
