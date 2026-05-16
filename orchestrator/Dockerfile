# === Build stage ===
FROM golang:1.22-alpine AS builder

WORKDIR /build

# Зависимости отдельным слоем для кэша
COPY go.mod go.sum ./
RUN go mod download

# Сборка статического бинарника
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build \
    -ldflags="-w -s" \
    -o /orchestrator ./cmd/orchestrator

# === Runtime stage ===
FROM alpine:3.20

# Non-root user (DEC-016 K8s manifests требование)
RUN addgroup -g 1000 orchestrator && \
    adduser -u 1000 -G orchestrator -s /sbin/nologin -D orchestrator

# CA-certs для HTTPS к OpenRouter (хоть и через summary-service)
RUN apk add --no-cache ca-certificates

COPY --from=builder /orchestrator /usr/local/bin/orchestrator

USER orchestrator
EXPOSE 8769

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD wget --quiet --tries=1 --spider http://127.0.0.1:8769/health || exit 1

ENTRYPOINT ["/usr/local/bin/orchestrator"]
