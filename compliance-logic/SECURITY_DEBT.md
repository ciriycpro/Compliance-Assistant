# Compliance Logic — Архитектурный долг

Реестр того что **намеренно отложено** для последующих коммитов. Обновляется на каждом коммите.

## Условные обозначения

- 🔴 Критично — блокирует production-нагрузку с реальными ПДн
- 🟡 Среднее — нужно перед production, но не блокирует разработку
- 🟢 Низкое — улучшение, можно делать когда есть время

## Долг по коммитам 1-2 (текущее состояние)

### 🔴 Критическое — закрыть перед production

| # | Что | Откуда | Когда планируем | Что делать |
|---|---|---|---|---|
| 1 | **Шифрование blob at-rest** через `age` | DEC-017 Уровень 2 | Перед production-нагрузкой с ПДн | Подключить age-encrypt в DocumentStorageService при записи/чтении blob. Ключ в Vault или /etc/age/key (chmod 600) |
| 2 | **GlobalExceptionHandler для DataIntegrityViolationException** | Smoke test 24.05.2026 — CHECK constraint возвращает 500 вместо 422 | Коммит 3 | Добавить `@ExceptionHandler(DataIntegrityViolationException.class)` → HTTP 422 с понятным JSON |
| 3 | **Backup стратегия для `/var/lib/compliance-files/`** | Operational | Перед production | Ежедневный tar + ротация 14 дней, в `/var/backups/compliance/`. Cron как у mail-stack |
| 4 | **Contract entity со SigningStatus** для коммита 4 | Обсуждение 24.05.2026 — подпись договоров | Коммит 4 | Поля: signing_status (DRAFT/SIGNED_ONE_SIDE/SIGNED_BOTH_SIDES/UNCLEAR/DISPUTED), client_signed/counterparty_signed boolean + dates, signature_confidence от vision-LLM |

### 🟡 Среднее — нужно перед vN.0 production

| # | Что | Откуда | Когда планируем |
|---|---|---|---|
| 5 | **K8s manifests** для compliance-logic | DEC-016 | По триггеру миграции (RAM на coo впритык) |
| 6 | **PersistentVolumeClaim** для `/var/lib/compliance-files/` в K8s | DEC-016 | Совместно с K8s manifests |
| 7 | **NetworkPolicy** в K8s — только orchestrator → compliance-logic | DEC-016 + DEC-017 L1 | Совместно с K8s manifests |
| 8 | **mTLS между сервисами** | DEC-017 Уровень 1 | После K8s или при появлении второго сервиса в цепочке (orchestrator → compliance-logic) |
| 9 | **Vault для secrets** | DEC-017 Уровень 2 | После K8s миграции |
| 10 | **Specifc rate limits для /statements и /money-operations** | DEC-017 Уровень 0 | По нагрузке (общий 600/min default защищает на v1.0) |
| 11 | **Springdoc OpenAPI 3 spec** | DEC-022 OpenAPI-first | Перед первым cross-service вызовом orchestrator → compliance-logic |

### 🟢 Низкое — улучшения

| # | Что | Откуда | Когда планируем |
|---|---|---|---|
| 12 | **OpenTelemetry tracing** (W3C traceparent + Jaeger) | DEC-023 v2.5 | По триггеру: 5+ сервисов в цепочке + debugging |
| 13 | **gRPC миграция** (Protobuf вместо JSON) | DEC-023 v5.0 | По триггеру производительности или строгих типов |
| 14 | **Spring Statemachine** для Statement lifecycle (RECEIVED→PARSED→VERIFIED/FLAGGED) | DEC-023 v2.0 | Коммит 4 (когда Reconciler — там же нужны переходы) |
| 15 | **CounterpartyClassifier agent** (LLM решает trust_level) | Дискуссия 24.05.2026 | Будущее (коммит 5-6 или KAMF v1) |
| 16 | **Cleanup orphan-документов из rate-limit smoke test** | 32 документа от вчерашнего тестирования | Перед production: `DELETE FROM documents WHERE original_filename LIKE 'rl-test-%'` |

## История долга

| Дата | Коммит | Закрыто | Открыто |
|---|---|---|---|
| 23.05.2026 | `8a828c8` Document master + Storage | systemd, ApiKey, JSON logs, CHECK на category (нет в текущей migration) | K8s, mTLS, Vault, blob encryption, Contract |
| 24.05.2026 | Текущий коммит 2 (Counterparty + Statement + MoneyOperation + Service слой + Envers + CHECK) | Envers @Audited, CHECK constraint, Service слой, GlobalExceptionHandler, парсенные поля для Reconciler | mTLS, Vault, blob encryption, K8s, Contract entity, DataIntegrityViolation→422 |

## Принципы работы с долгом

1. **Долг не фиксируем как "будет когда-нибудь"** — у каждого пункта есть **триггер** для закрытия
2. **Перед каждым коммитом** — review этого файла и обновление статуса
3. **Критический долг не накапливается** — закрывается в ближайшем коммите или явно эскалируется до 🔴
4. **Если что-то превращается в block** — поднимаем эскалацию: пересмотр архитектуры или приостановка фичи

## Ссылки

- DEC-007 (Docker-friendly): https://github.com/ciriycpro/architect/blob/main/tairov/decisions/0007-deployment-form.md
- DEC-016 (Kubernetes manifests): https://github.com/ciriycpro/architect/blob/main/tairov/decisions/0016-kubernetes-manifests.md
- DEC-017 (Secure by Design): https://github.com/ciriycpro/architect/blob/main/tairov/decisions/0017-secure-by-design.md
- DEC-022 (Mail-stack as Platform): https://github.com/ciriycpro/architect/blob/main/tairov/decisions/0022-mail-stack-as-platform.md
- DEC-023 (Compliance Logic Layer): https://github.com/ciriycpro/architect/blob/main/tairov/decisions/0023-compliance-logic-layer.md
