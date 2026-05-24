# Compliance Logic — Архитектурный долг

Реестр того что намеренно отложено для последующих коммитов. Обновляется на каждом коммите.

## Условные обозначения

- 🔴 Критично — блокирует production-нагрузку с реальными ПДн
- 🟡 Среднее — нужно перед production, но не блокирует разработку
- 🟢 Низкое — улучшение, можно делать когда есть время

## Долг по коммитам 1-3.6 (текущее состояние, 25.05.2026 00:15 МСК)

### 🔴 Критическое — закрыть перед production

| # | Что | Когда |
|---|---|---|
| 1 | Шифрование blob at-rest через age (DEC-017 Уровень 2) | Перед production с реальными ПДн |
| 4 | Contract entity со SigningStatus | Коммит 4 |

### 🟡 Среднее — нужно перед vN.0 production

| # | Что | Когда |
|---|---|---|
| 9 | Vault для secrets | После K8s миграции |
| 10 | Specific rate limits для /statements и /money-operations | По нагрузке |
| 11 | Springdoc OpenAPI 3 spec | Перед orchestrator integration (коммит 5) |
| 20 | mTLS client-side для orchestrator/mail-stack | Коммит 5 или 6 |
| 21 | Inspector таймзона (сейчас cron в UTC) | Коммит 4 |
| 24 | Backfill graceful shutdown | Перед multi-tenant |
| 25 | Backfill rate limiting per-client | Перед multi-tenant |
| 26 | systemd PrivateTmp documented constraint | Документация |
| 27 | Hardcoded "12 months lookback" в Inspector — вынести в inspector.lookback.months env | Коммит 4 |
| 28 | Inspector scans не пишутся в ComplianceEvent (нет INSPECTOR_SCAN_COMPLETED события) | Коммит 4 |
| 29 | StatementCalendar не валидирует bank trust_level (можно создать на FLAGGED counterparty) | Открытый вопрос |

### 🟢 Низкое — улучшения

| # | Что | Когда |
|---|---|---|
| 12 | OpenTelemetry tracing | DEC-023 v2.5 |
| 13 | gRPC миграция | DEC-023 v5.0 |
| 14 | Spring Statemachine для Statement/Contract lifecycle | Коммит 4 |
| 15 | CounterpartyClassifier agent | KAMF v1 |
| 16 | Cleanup orphan-документов | Перед production |
| 22 | Sequence revinfo_seq incrementBy=50 | При миграции на свежую среду |
| 23 | CI/CD pipeline (GitHub Actions) | Когда команда >1 |
| 30 | StatementCalendar soft-delete без deleted_at timestamp | Через Envers _aud видно |

## Закрытое (история коммитов)

- 8a828c8 (23.05.2026): systemd, ApiKey, JSON logs, path traversal, sha256 dedup, Bucket4j rate limit, CORS, transactional cleanup
- ad29636 (24.05.2026): ReestrService слой, Counterparty/Statement/MoneyOperation, GlobalExceptionHandler 16 типов, Envers, CHECK constraints
- bdcb5d4 (24.05.2026): StatementGap + ComplianceEvent + Inspector + Scheduler, mTLS server-side, K8s manifests, Backup cron
- d6da726 (24.05.2026): BackfillJob + Service + Controller, GDrive import utility, GlobalExceptionHandler 24 типа, path whitelist
- 5dfd6dc (25.05.2026): #17 Client.monitoring_period_start, #18 StatementCalendar entity + Inspector v2 calendar-based scan, 28 типов exceptions

## Принципы

1. Долг не фиксируется как "будет когда-нибудь" — у каждого пункта есть триггер
2. Перед каждым коммитом — review этого файла
3. Критический долг не накапливается
4. Документировать learnings даже если "закрыт переездом"
5. Аудит DEC-007/016/017 после каждого коммита, новые долги фиксируются

## Ссылки

DEC-007, 014, 016, 017, 022, 023 в https://github.com/ciriycpro/architect/blob/main/tairov/decisions/
