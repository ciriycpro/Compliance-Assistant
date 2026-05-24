# Compliance Logic — Архитектурный долг

Реестр того что намеренно отложено для последующих коммитов. Обновляется на каждом коммите.

## Условные обозначения

- 🔴 Критично — блокирует production-нагрузку с реальными ПДн
- 🟡 Среднее — нужно перед production, но не блокирует разработку
- 🟢 Низкое — улучшение, можно делать когда есть время

## Долг по коммитам 1-3.5 (текущее состояние, 24.05.2026 23:30 МСК)

### 🔴 Критическое — закрыть перед production

| # | Что | Когда планируем |
|---|---|---|
| 1 | Шифрование blob at-rest через age (DEC-017 Уровень 2) | Перед production с реальными ПДн |
| 4 | Contract entity со SigningStatus (DRAFT/SIGNED_ONE_SIDE/SIGNED_BOTH_SIDES/UNCLEAR/DISPUTED) | Коммит 4 |
| 17 | Client.monitoring_period_start — дата с которой Inspector смотрит | Коммит 3.6 (следующий) |
| 18 | StatementCalendar entity (MONTHLY/QUARTERLY/ANNUAL) для expected periods | Коммит 3.6 (следующий) |

### 🟡 Среднее — нужно перед vN.0 production

| # | Что | Когда планируем |
|---|---|---|
| 9 | Vault для secrets (DEC-017 Уровень 2) | После K8s миграции |
| 10 | Specific rate limits для /statements и /money-operations | По нагрузке |
| 11 | Springdoc OpenAPI 3 spec (DEC-022) | Перед orchestrator integration (коммит 5) |
| 20 | mTLS client-side для orchestrator/mail-stack | Коммит 5 или 6 |
| 21 | Inspector таймзона (сейчас cron в UTC) | Коммит 4 |
| 24 | Backfill graceful shutdown (cancel при SIGTERM) | Перед multi-tenant |
| 25 | Backfill rate limiting per-client (1 active job per client) | Перед multi-tenant |
| 26 | systemd PrivateTmp documented constraint | Документация (закрыто переездом на /var/lib/compliance-files/import/) |

### 🟢 Низкое — улучшения

| # | Что | Когда планируем |
|---|---|---|
| 12 | OpenTelemetry tracing (W3C traceparent + Jaeger) | DEC-023 v2.5 |
| 13 | gRPC миграция (Protobuf вместо JSON) | DEC-023 v5.0 |
| 14 | Spring Statemachine для Statement/Contract lifecycle | Коммит 4 |
| 15 | CounterpartyClassifier agent (LLM решает trust_level) | KAMF v1 |
| 16 | Cleanup orphan-документов (32 от rate-limit smoke) | Перед production |
| 22 | Sequence revinfo_seq incrementBy=50 при первичной миграции | При миграции на свежую среду |
| 23 | CI/CD pipeline (Maven build на push, GitHub Actions) | Когда команда >1 |

## Закрытое (история коммитов)

- `8a828c8` (23.05.2026): systemd unit, ApiKey, JSON logs, path traversal, sha256 dedup, Bucket4j rate limit, CORS, transactional orphan cleanup
- `ad29636` (24.05.2026): ReestrService слой, Counterparty/Statement/MoneyOperation, GlobalExceptionHandler 16 типов exceptions, Envers @Audited, CHECK constraint, парсенные поля для Reconciler
- `bdcb5d4` (24.05.2026): StatementGap + ComplianceEvent + Inspector + Scheduler, idempotency, mTLS server-side (internal CA + PKCS12), K8s manifests 8 файлов, Backup cron, DataIntegrityViolation → 422
- `d6da726` (24.05.2026): BackfillJob + Service + Controller (DEC-023 v1.5), GDrive import utility (Python ~250 строк), GlobalExceptionHandler unified (24 типа), DocumentService.createFromBytes, path whitelist /var/lib/compliance-files/import/

## Принципы

1. Долг не фиксируется как "будет когда-нибудь" — у каждого пункта есть триггер
2. Перед каждым коммитом — review этого файла
3. Критический долг не накапливается
4. Документировать learnings (PrivateTmp, incrementBy=50) даже если "закрыт переездом"

## Ссылки

- DEC-007, 014, 016, 017, 022, 023 в https://github.com/ciriycpro/architect/blob/main/tairov/decisions/
