// Package ratelimit — token bucket per-endpoint (Secure by Design Уровень 0, DEC-017).
// Защита от DoS-flood: spam-запросы /digest-now могут разорить OpenRouter-баланс.
package ratelimit

import (
	"encoding/json"
	"net/http"
	"sync"
	"time"
)

// Limiter — простой token-bucket per-endpoint (без per-IP на v1).
type Limiter struct {
	mu         sync.Mutex
	tokens     float64
	maxTokens  float64
	refillRate float64 // токенов в секунду
	lastRefill time.Time
}

// NewLimiter — создаёт limiter с указанным rate per minute.
func NewLimiter(perMinute int) *Limiter {
	max := float64(perMinute)
	return &Limiter{
		tokens:     max,
		maxTokens:  max,
		refillRate: max / 60.0,
		lastRefill: time.Now(),
	}
}

// Allow — пытается забрать токен. Возвращает true если запрос разрешён.
func (l *Limiter) Allow() bool {
	l.mu.Lock()
	defer l.mu.Unlock()

	now := time.Now()
	elapsed := now.Sub(l.lastRefill).Seconds()
	l.tokens += elapsed * l.refillRate
	if l.tokens > l.maxTokens {
		l.tokens = l.maxTokens
	}
	l.lastRefill = now

	if l.tokens < 1 {
		return false
	}
	l.tokens--
	return true
}

// Middleware — HTTP middleware-обёртка.
func (l *Limiter) Middleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !l.Allow() {
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusTooManyRequests)
			json.NewEncoder(w).Encode(map[string]string{
				"error": "rate limit exceeded, try again later",
			})
			return
		}
		next.ServeHTTP(w, r)
	})
}
