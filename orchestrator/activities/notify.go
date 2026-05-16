// Notify activity: уведомление Agent Caller о завершении workflow (push-pattern для UX timers).
//
// Зачем: Agent Caller запускает прогресс-таймеры ("📚 Тут немало документов...") после нажатия
// кнопки и НЕ знает когда workflow завершился (orchestrator работает async, Telegram идёт прямо
// от него). Чтобы Agent Caller отменил таймеры — orchestrator пушит /workflow-done после успешной
// доставки.
//
// Архитектурно это паттерн event-driven (push), а не polling — без задержек реакции.
// На v2 (Temporal) — заменится на Temporal Signal к Agent Caller worker'у.
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

// NotifyActivity — клиент к Agent Caller для push-уведомлений о workflow.
type NotifyActivity struct {
	BaseURL string // http://127.0.0.1:3000
	Timeout time.Duration
}

// NewNotifyActivity конструктор.
func NewNotifyActivity(baseURL string, timeout time.Duration) *NotifyActivity {
	return &NotifyActivity{BaseURL: baseURL, Timeout: timeout}
}

// WorkflowDoneParams — параметры уведомления.
type WorkflowDoneParams struct {
	ChatID  string // строковое представление chat_id
	TraceID string
	Status  string // "delivered" | "no_messages" | "failed"
}

// WorkflowDone — уведомляет Agent Caller что workflow завершён, чтобы он отменил UX-таймеры.
//
// Ошибки не критичны для workflow: если Agent Caller недоступен — таймеры останутся и просто
// отработают по timeout (через 11 минут), что приемлемо. Логируем warning, не error.
func (n *NotifyActivity) WorkflowDone(ctx context.Context, params WorkflowDoneParams, opts CallOptions) error {
	body, err := json.Marshal(map[string]string{
		"chat_id":  params.ChatID,
		"trace_id": params.TraceID,
		"status":   params.Status,
	})
	if err != nil {
		return fmt.Errorf("marshal: %w", err)
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodPost,
		n.BaseURL+"/workflow-done", bytes.NewReader(body))
	if err != nil {
		return fmt.Errorf("create request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	if opts.TraceID != "" {
		req.Header.Set("X-Trace-Id", opts.TraceID)
	}

	client := newHTTPClient(resolveTimeout(opts, n.Timeout))
	resp, err := client.Do(req)
	if err != nil {
		return fmt.Errorf("agent-caller: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		errBody, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("agent-caller returned %d: %s", resp.StatusCode, string(errBody))
	}
	return nil
}

// WorkflowProgressParams — параметры события прогресса.
type WorkflowProgressParams struct {
	ChatID  string
	TraceID string
	Step    string         // "attachments_start" | "summary_start" — semantic name события
	Meta    map[string]any // произвольные метаданные: count, elapsed_ms, size_bytes, ...
}

// WorkflowProgress — событие прогресса workflow.
//
// Agent Caller получает событие и решает (на основе step+meta) что показать пользователю.
// Например:
//   - step="attachments_start", meta={count: 9} → "📚 Тут немало документов..."
//   - step="summary_start", meta={elapsed_ms: 120000} → "⏳ Почти готово..."
//
// Логика "что показать" живёт в Agent Caller, не в orchestrator. Это позволяет
// менять UX-тексты и пороги без переделки orchestrator.
//
// Ошибки не критичны: если Agent Caller недоступен, юзер просто не увидит прогресс-сообщение.
// Workflow продолжается.
func (n *NotifyActivity) WorkflowProgress(ctx context.Context, params WorkflowProgressParams, opts CallOptions) error {
	payload := map[string]any{
		"chat_id":  params.ChatID,
		"trace_id": params.TraceID,
		"step":     params.Step,
	}
	if params.Meta != nil {
		payload["meta"] = params.Meta
	}

	body, err := json.Marshal(payload)
	if err != nil {
		return fmt.Errorf("marshal: %w", err)
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodPost,
		n.BaseURL+"/workflow-progress", bytes.NewReader(body))
	if err != nil {
		return fmt.Errorf("create request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	if opts.TraceID != "" {
		req.Header.Set("X-Trace-Id", opts.TraceID)
	}

	client := newHTTPClient(resolveTimeout(opts, n.Timeout))
	resp, err := client.Do(req)
	if err != nil {
		return fmt.Errorf("agent-caller: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		errBody, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("agent-caller returned %d: %s", resp.StatusCode, string(errBody))
	}
	return nil
}
