// Package auth — X-API-Key middleware (Secure by Design Уровень 0, DEC-017).
// На v2 — заменится на mTLS между сервисами.
package auth

import (
	"crypto/subtle"
	"encoding/json"
	"net/http"
)

// APIKeyMiddleware — проверяет заголовок X-API-Key.
// Использует constant-time comparison для защиты от timing attacks.
func APIKeyMiddleware(expectedKey string) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			got := r.Header.Get("X-API-Key")
			if subtle.ConstantTimeCompare([]byte(got), []byte(expectedKey)) != 1 {
				w.Header().Set("Content-Type", "application/json")
				w.WriteHeader(http.StatusUnauthorized)
				json.NewEncoder(w).Encode(map[string]string{
					"error": "invalid or missing X-API-Key",
				})
				return
			}
			next.ServeHTTP(w, r)
		})
	}
}
