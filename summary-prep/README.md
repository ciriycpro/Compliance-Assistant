# summary-prep v1.0

Микросервис дистилляции длинных документов через map-reduce + Claude Haiku 4.5 via OpenRouter.

См. **DEC-0030** в `architect` репо (`tairov/decisions/0030-summary-prep-service.md`).

## Запуск

### Локально

```bash
cp deploy/env-templates/summary-prep.env.template /tmp/summary-prep.env
# Заполнить OPENROUTER_API_KEY, SUMMARY_PREP_API_KEY
export $(cat /tmp/summary-prep.env | grep -v '^#' | xargs)
python -m venv venv
source venv/bin/activate
pip install -r requirements.txt
python server.py
```

### Docker

```bash
docker build -t summary-prep:v1 .
docker run --rm \
    -p 8772:8772 \
    -e OPENROUTER_API_KEY=sk-or-v1-... \
    -e SUMMARY_PREP_API_KEY=... \
    -v summary-prep-cache:/var/lib/summary-prep/cache \
    summary-prep:v1
```

### systemd (production на coo)

См. `deploy/systemd/summary-prep.service`.

## Endpoints

- `POST /distill` — главный endpoint (требует `X-API-Key`)
- `GET /health` — liveness
- `GET /readiness` — readiness (cache dir + API keys + OpenRouter ping)
- `GET /metrics` — счётчики (требует `X-API-Key`)

## Режимы (env `DISTILL_MODE`)

- `production` (по умолчанию) — реальные LLM-calls
- `canary` — реальные LLM-calls только при совпадении `canary_token` в request body c env `DISTILL_CANARY_TOKEN`
- `mock` — заранее заготовленный DistillResult без LLM-calls (для тестов orchestrator)

## Пример запроса

```bash
curl -X POST http://127.0.0.1:8772/distill \
    -H "X-API-Key: $SUMMARY_PREP_API_KEY" \
    -H "X-Trace-Id: 019ef-test-001" \
    -H "Content-Type: application/json" \
    -d '{
        "text": "Длинный текст документа...",
        "metadata": {
            "from": "noreply@crediteurope.ru",
            "subject": "Ответ банку 115-ФЗ",
            "date": "2026-06-17T09:42:23+03:00",
            "attachment_filename": "Ответ_банку.pdf"
        },
        "quality_mode": "fast",
        "contract_strictness": "soft"
    }'
```

## Архитектура

См. DEC-0030 для полной картины. Краткая схема:

```
parser-service → orchestrator → summary-prep → summary-service
                                    ↓
                            OpenRouter (Haiku 4.5)
                                    ↓
                       /var/lib/summary-prep/cache/ (sha256 FS-cache)
```
