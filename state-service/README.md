# state-service

Микросервис состояния для mail-stack. Часть архитектуры DEC-013 (Mail Check On-Demand) + DEC-022 (Mail-stack as platform).

## Что хранит

Per chat_id:
- **last_at** — timestamp последнего успешного дайджеста (без TTL, persistent)
- **lock** — флаг "workflow выполняется" с TTL (защита от двойного клика)

Используется orchestrator'ом (и в будущем compliance-logic) для incremental digest flow:
- Cron / кнопка стартуют → `AcquireLock` → если конфликт → пользователю «уже обрабатываю»
- Workflow читает `last_at`, считает `since = last_at`, `until = now()`
- После успешной доставки в Telegram → `SetLastAt(now())` → `ReleaseLock`

## API

Auth: `X-API-Key` header на все `/state/*` endpoints.

| Method | Path | Описание |
|---|---|---|
| GET | `/health` | без auth, проверяет Redis ping |
| GET | `/state/{chat_id}/last_at` | last_at или 404 |
| POST | `/state/{chat_id}/last_at` | body `{"timestamp":"..."}` (или пустой = now) |
| POST | `/state/{chat_id}/lock` | body `{"ttl_seconds":300}` (опционально); 409 если занят |
| DELETE | `/state/{chat_id}/lock` | snять lock (idempotent) |
| GET | `/state/{chat_id}/status` | last_at + locked + lock_ttl_seconds |

## Запуск

### Локально (для тестов)

```bash
export STATE_SERVICE_API_KEY="must-be-at-least-16-chars-long"
export REDIS_ADDR="127.0.0.1:6379"
go run ./cmd/state-service
```

### Systemd на coo

```bash
sudo cp state-service-bin /opt/mail-stack/state-service/state-service-bin
sudo cp deploy/state-service.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable state-service
sudo systemctl start state-service
```

## Тесты

```bash
# Запустить Redis локально на :6379, тесты используют DB=15 (изолированный namespace)
go test ./...
```

## Smoke (curl)

```bash
API_KEY="..."

# Health
curl http://127.0.0.1:8770/health

# Set last_at
curl -X POST http://127.0.0.1:8770/state/249979054/last_at \
  -H "X-API-Key: $API_KEY" \
  -H "Content-Type: application/json" \
  -d '{}'

# Get last_at
curl http://127.0.0.1:8770/state/249979054/last_at \
  -H "X-API-Key: $API_KEY"

# Acquire lock
curl -X POST http://127.0.0.1:8770/state/249979054/lock \
  -H "X-API-Key: $API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"ttl_seconds": 60}'

# Status
curl http://127.0.0.1:8770/state/249979054/status \
  -H "X-API-Key: $API_KEY"

# Release lock
curl -X DELETE http://127.0.0.1:8770/state/249979054/lock \
  -H "X-API-Key: $API_KEY"
```

## Resource footprint

- Binary: ~12 МБ статичный
- RAM: ~8 МБ runtime
- Boot: <500 мс
- Redis ключи: фиксированный паттерн `state:{chat_id}:last_at` и `state:{chat_id}:lock`
