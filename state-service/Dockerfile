# Multi-stage build для state-service.
# Финальный образ ~10 МБ (Go static binary в scratch).
#
# Docker-friendly паттерн (DEC-007):
# - Listen 0.0.0.0 в Docker (через ENV override)
# - Логи в stdout (slog)
# - Healthcheck endpoint /health
# - Env-driven конфигурация
# - Pinned зависимости (go.sum)
# - Нет хардкода путей
# - Graceful shutdown SIGTERM

FROM golang:1.22-alpine AS builder
WORKDIR /build

# Зависимости отдельно для кэша Docker слоёв
COPY go.mod go.sum* ./
RUN go mod download

# Исходный код
COPY . .

# Сборка статичного бинарника
RUN CGO_ENABLED=0 GOOS=linux go build \
    -ldflags="-s -w" \
    -o state-service \
    ./cmd/state-service

# === Final stage ===
FROM scratch
COPY --from=builder /build/state-service /state-service

# В Docker слушаем 0.0.0.0, не 127.0.0.1 (правило 2 DEC-007).
# При запуске через systemd на хосте — env-файл переопределяет на 127.0.0.1.
ENV STATE_SERVICE_HTTP_HOST=0.0.0.0
ENV STATE_SERVICE_HTTP_PORT=8770

# Non-root user (uid 1000)
USER 1000:1000

EXPOSE 8770

# Healthcheck — Docker сам опрашивает /health.
# Note: scratch image не содержит wget/curl. Полагаемся на Docker engine healthcheck
# либо K8s livenessProbe + readinessProbe (см. deploy/kubernetes/).

ENTRYPOINT ["/state-service"]

