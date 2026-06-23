# Compliance Assistant — production code

Production code for **Compliance Assistant** (DEC-0014 + DEC-0023) — multi-service compliance stack running on coo (GCP e2-small).

## Архитектура и решения

Архитектурные решения (ADR, C4 diagrams, Service Blueprint) живут отдельно:  
**https://github.com/ciriycpro/architect** — workspace `tairov/`

Здесь — production-код сервисов в их текущем состоянии. Когда нужен контекст «почему так» — смотри ADR в `architect` репо.

## Сервисы

| Сервис | Стек | Порт | Версия | ADR |
|---|---|---|---|---|
| `orchestrator/` | Go 1.22 + net/http + cron + slog | 8769 | v1.2.2 (в проде email_digest_v1; statement_vacuum_v1 в working tree) | DEC-0014, DEC-0027 |
| `state-service/` | Go 1.22 + go-redis + chi | 8770 | v1.0 | DEC-0021 |
| `mail-service/` | Python 3.11 + FastAPI + IMAP, label/group routing | 8765 | v1.2 | DEC-0007, DEC-0022 |
| `attachment-service/` | Python 3.11 + FastAPI, label/group filter | 8766 | v1.1 | DEC-0011, DEC-0022 |
| `parser-service/` | Python 3.10 + FastAPI + LLM-vision, `/parse-statement` + statement_parser.py + statement_xlsx.py | 8767 | v1.1 | DEC-0008, DEC-024, DEC-0027 |
| `summary-service/` | Python 3.11 + FastAPI + Claude Haiku 4.5 | 8768 | v1.0 | DEC-0009 |
| `agent-caller/` | Node.js + whatsapp-web.js + node-telegram-bot-api | 3000 | v1.2.2 | DEC-0005, DEC-018 |
| `compliance-logic/` | Java 21 + Spring Boot 3.5 + Postgres + Liquibase, mTLS | 8771 | v0.0.7-SNAPSHOT (jar 04.06.2026) | DEC-0023, DEC-026 |

`compliance-logic` — бизнес-tier (Spring): source-of-truth БД и реестр сущностей. На 23.06.2026: 14 entity, 17 service-классов, 29 таблиц, 23 миграции, ~46 REST endpoints.

## Production state на 23.06.2026 (из БД)

| Таблица | Записей |
|---|---|
| `clients` | 2 (Таиров, Веретенникова) |
| `counterparties` | 34 |
| `documents` | 39 |
| `statements` | 6 |
| `money_operations` | 936 |
| `reconciliation_flags` | 26 (MISSING_CONTRACT, DETECTED) |
| `notifications` | 38 (исходящие WA-алёрты) |
| `statement_gaps` | 23 |
| `contracts`, `acts` | 0 (DEC-0028 не задеплоен) |

## Архитектурный поток (production)

Два независимых контура:

**Контур 1 — Mail Reader дайджест (DEC-0014 + DEC-013):**
```
Cron / TG-кнопка ──► orchestrator (workflow email_digest_v1)
                          │
                          ├─► mail-service ───► IMAP
                          ├─► attachment-service ──► IMAP
                          ├─► parser-service ─► OpenRouter (Qwen-VL)
                          ├─► summary-service ─► OpenRouter (Haiku 4.5)
                          ├─► state-service ─► Redis (last_at, lock)
                          └─► agent-caller ──► WhatsApp pre-alert + Telegram дайджест
```

**Контур 2 — Compliance gap-alert outbound (DEC-0027 alert-loop):**
```
compliance-logic (OrchestratorScheduler, cron 16:30 МСК)
       │
       ├─► InspectorService.scanClient — обнаруживает statement_gaps
       ├─► ReconcilerService (cron 10:30 МСК) — создаёт MISSING_CONTRACT флаги
       ├─► GapAlertOrchestrator — алёрт-loop по открытым gaps с фильтром по flag_type
       │       │
       │       └─► HttpCallerClient (CallerPort) ──► agent-caller ──► WhatsApp Таирову
       │
       └─► NotificationService — audit-журнал (38 записей в проде)
```

**В working tree, не задеплоено (Шаг 3b санации):**

**Контур 3 — statement-vacuum (DEC-0027 Open Issue #1):**
- `orchestrator/workflow/statement_vacuum_v1.go` + `activities/ingest.go` + второй cron `c2`.
- Цель: автоматический ingest выписок из почты → `compliance-logic POST /statements/ingest` → автозакрытие statement_gaps.
- Замыкает входящий контур, который сейчас работает только при ручной отправке через `BackfillService`.

**DEC-0028 Phase 1 Java правки (working tree):**
- `ReconcilerService.rescanForContract` + bulk-линковка.
- `ContractService` post-ingest hook.
- `MoneyOperationRepository.findByClient...IsNull`.
- `HttpCallerClient` timeout 120→300с (DEC-0027 Open #3 fix).

## Deployment

Сервисы развёрнуты через systemd на coo. Env-файлы в `/etc/mail-stack/*.env` (chmod 600 root:root) **не в git** — шаблоны лежат в `deploy/env-templates/`.

Unit-файлы systemd в `deploy/systemd/`.

**Деплой Go-сервисов** (см. DEC-0014 Implementation Notes 23.06.2026):
```bash
cd ~/compliance-assistant-repo/orchestrator
go build -trimpath -ldflags="-s -w" -o orchestrator-bin ./cmd/orchestrator
sudo cp /opt/mail-stack/orchestrator/orchestrator-bin /opt/mail-stack/orchestrator/orchestrator-bin.bak.$(date +%s)
sudo cp orchestrator-bin /opt/mail-stack/orchestrator/orchestrator-bin
sudo systemctl restart orchestrator
```

**Деплой compliance-logic** — `mvn package` + замена jar + `systemctl restart compliance-logic`.

## Версионирование

Это **зеркало production-состояния**, не полная git-история разработки. Коммиты в этот репо делаются при major изменениях в production через кнопку `sync-code-from-coo.command` (rsync `/opt/mail-stack/{mail,parser,attachment}-service/*.py` в working tree + `git add/commit/push`). История архитектурных решений — в `architect` repo (ADR + DSL).

## Что НЕ в репо

- `*.env` файлы (секреты)
- `node_modules/`, `venv/`, `__pycache__/`, бинарники, `*.whl`
- WhatsApp Web сессия (`agent-caller/.wwebjs_auth/`)
- Логи, бэкапы (`*.bak.*`)
- Compiled jar/bin (только source code)

## Каноны

См. **DEC-0007** (docker-friendly), **DEC-0014** (полиглот-стек + orchestrator Go custom v1 → Temporal v2 → KAMF v3), **DEC-017** (secure-by-design), **DEC-0022** (mail-stack as platform с label/group адресацией), **DEC-024** (mail-stack v1.1 fixes), **DEC-026** (rate-limit per tenant), **DEC-0027** (alert-loop + statement-vacuum), **DEC-0028** (contract/act vacuum, Drafted) в репо `architect`. Техдолг в `architect/tairov/decisions/cleanup_backlog_v2.md` (21 пункт по 5 разделам).

## Контакт

- Артём Якшин, founder СИРИУС ПРО
- inbox@ciriyc.ru
- Telegram: @Economexer
