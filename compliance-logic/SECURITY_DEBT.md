# Compliance Logic — Архитектурный долг

Реестр того что **намеренно отложено** для последующих коммитов. Обновляется на каждом коммите.

## Условные обозначения

- 🔴 Критично — блокирует production-нагрузку с реальными ПДн
- 🟡 Среднее — нужно перед production, но не блокирует разработку
- 🟢 Низкое — улучшение, можно делать когда есть время

## Долг по коммитам 1-3 (текущее состояние, обновлено 24.05.2026 21:00 МСК)

### 🔴 Критическое — закрыть перед production

| # | Что | Откуда | Когда планируем | Что делать |
|---|---|---|---|---|
| 1 | **Шифрование blob at-rest** через `age` | DEC-017 Уровень 2 | Перед production-нагрузкой с реальными ПДн (после коммита 3.6) | Подключить age-encrypt в DocumentStorageService при записи/чтении blob. Ключ в Vault или /etc/age/key (chmod 600). Сейчас на coo тестовые данные — не блокер |
| 4 | **Contract entity со SigningStatus** для коммита 4 | Обсуждение 24.05.2026 — подпись договоров | Коммит 4 | Поля: signing_status (DRAFT/SIGNED_ONE_SIDE/SIGNED_BOTH_SIDES/UNCLEAR/DISPUTED), client_signed/counterparty_signed boolean + dates, signature_confidence от vision-LLM |
| 17 | **Client.monitoring_period_start** | Обсуждение 24.05.2026 — Inspector без даты начала видит «пробелы» в 1995 году | Коммит 3.5 | @Column LocalDate monitoring_period_start в Client entity. Inspector использует max(monitoring_period_start, current_year - 12 months) как нижнюю границу |
| 18 | **StatementCalendar entity** для expected periods | Обсуждение 24.05.2026 — без него Inspector детектит только gaps **между** существующими, не «нет выписки в апреле» | Коммит 3.5 | Entity с client_id + bank_id + frequency (MONTHLY/QUARTERLY/ANNUAL) + start_period. Inspector генерирует expected periods на лету и сравнивает с existing |
| 19 | **Google Drive import утилита** | Обсуждение 24.05.2026 — массив реальных документов Таирова на GDrive | Коммит 3.5 | Python скрипт через rclone/gdrive CLI. Скачивает архив в /var/lib/compliance-files/import/ → bulk POST /documents с дедупом по sha256 |

### 🟡 Среднее — нужно перед vN.0 production

| # | Что | Откуда | Когда планируем |
|---|---|---|---|
| 9 | **Vault для secrets** | DEC-017 Уровень 2 | После K8s миграции |
| 10 | **Specific rate limits для /statements и /money-operations** | DEC-017 Уровень 0 | По нагрузке (общий 600/min default защищает на v1.0) |
| 11 | **Springdoc OpenAPI 3 spec** | DEC-022 OpenAPI-first | Перед первым cross-service вызовом orchestrator → compliance-logic (коммит 5) |
| 20 | **mTLS client-side для orchestrator/mail-stack** | Обсуждение 24.05.2026 — server side готов (compliance-logic.p12), нужны client certs для других сервисов | Коммит 5 или 6 (когда orchestrator начнёт звать) | Issue client cert для каждого сервиса через тот же CA. Spring server.ssl.client-auth=need после переключения всех |
| 21 | **Inspector таймзона** | Обсуждение 24.05.2026 — cron `0 0 10 * * *` читается в UTC, на coo UTC, так что 10:00 UTC = 13:00 МСК | Коммит 4 | Добавить `inspector.timezone=Europe/Moscow` + конфиг Spring scheduling timezone |

### 🟢 Низкое — улучшения

| # | Что | Откуда | Когда планируем |
|---|---|---|---|
| 12 | **OpenTelemetry tracing** (W3C traceparent + Jaeger) | DEC-023 v2.5 | По триггеру: 5+ сервисов в цепочке + debugging |
| 13 | **gRPC миграция** (Protobuf вместо JSON) | DEC-023 v5.0 | По триггеру производительности или строгих типов |
| 14 | **Spring Statemachine** для Statement lifecycle (RECEIVED→PARSED→VERIFIED/FLAGGED) | DEC-023 v2.0 | Коммит 4 (когда Reconciler — там же нужны переходы) |
| 15 | **CounterpartyClassifier agent** (LLM решает trust_level) | Дискуссия 24.05.2026 | Будущее (коммит 5-6 или KAMF v1) |
| 16 | **Cleanup orphan-документов из rate-limit smoke test** | 32 документа от вчерашнего тестирования | Перед production: `DELETE FROM documents WHERE original_filename LIKE 'rl-test-%'` |
| 22 | **Sequence revinfo_seq incrementBy=50 при первичной миграции** | Обсуждение 24.05.2026 — Liquibase 0006 создаёт sequence с incrementBy=1, Envers ждёт 50. На coo ALTER SEQUENCE сделан вручную | При миграции на свежую среду | Обновить 0006 чтобы создавать сразу с incrementBy=50 |
| 23 | **CI/CD pipeline (Maven build на push)** | GitHub Actions suggested workflows | Когда команда >1 человека или коммитов много | GitHub Actions: на push → mvnw package → unit tests → docker build → SCP на coo |

## Закрытое (история)

| Коммит | Дата | Что закрыто |
|---|---|---|
| `8a828c8` | 23.05.2026 | systemd unit, ApiKey, JSON logs, path traversal protection, sha256 dedup, Bucket4j rate limit (общий 600/min), CORS policy explicit, transactional orphan file cleanup |
| `ad29636` | 24.05.2026 (середина) | ReestrService pattern (Service слой), Counterparty/Statement/MoneyOperation entity, GlobalExceptionHandler типизированных exceptions, Envers @Audited, CHECK constraint на category, парсенные поля для Reconciler |
| `bdcb5d4` | 24.05.2026 (вечер) | StatementGap + ComplianceEvent entity, Inspector + Scheduler (cron), idempotency check, mTLS server-side (internal CA + PKCS12), K8s manifests namespace+8 файлов, Backup cron + ротация 14 дней, GlobalExceptionHandler DataIntegrityViolation → 422 |

## Принципы работы с долгом

1. **Долг не фиксируется как "будет когда-нибудь"** — у каждого пункта есть **триггер** для закрытия
2. **Перед каждым коммитом** — review этого файла и обновление статуса
3. **Критический долг не накапливается** — закрывается в ближайшем коммите или явно эскалируется до 🔴
4. **Если что-то превращается в block** — поднимаем эскалацию: пересмотр архитектуры или приостановка фичи
5. **Если долг закрыт частично** — фиксируем в "🟡 Medium" то что осталось (например mTLS server side → 🟢, client-side → 🟡)

## Ссылки

- DEC-007 (Docker-friendly): https://github.com/ciriycpro/architect/blob/main/tairov/decisions/0007-deployment-form.md
- DEC-016 (Kubernetes manifests): https://github.com/ciriycpro/architect/blob/main/tairov/decisions/0016-kubernetes-manifests.md
- DEC-017 (Secure by Design): https://github.com/ciriycpro/architect/blob/main/tairov/decisions/0017-secure-by-design.md
- DEC-022 (Mail-stack as Platform): https://github.com/ciriycpro/architect/blob/main/tairov/decisions/0022-mail-stack-as-platform.md
- DEC-023 (Compliance Logic Layer): https://github.com/ciriycpro/architect/blob/main/tairov/decisions/0023-compliance-logic-layer.md (Implementation Notes v0.0.3-SNAPSHOT)
