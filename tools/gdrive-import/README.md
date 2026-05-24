# Google Drive Import Utility

Скачивает архив документов клиента из Google Drive и загружает их в compliance-logic через REST API.

## Workflow
GDrive folder
↓ (rclone)
/tmp/gdrive-import-<ts>/
↓ (parallel POST /documents)
compliance-logic (deduplication через sha256)
↓
Postgres + /var/lib/compliance-files/<inn>/<type>/

## Установка зависимостей (на coo, один раз)

```bash
# rclone — уже установлен (см. tools/gdrive-import/README.md установка)
# Python пакеты
pip install requests tqdm --break-system-packages
```

## Настройка rclone для GDrive (one-time)

```bash
rclone config
# Шаги:
#   n) New remote
#   name: tairov-gdrive  (или любое имя; ниже используем как --remote)
#   storage: 18 (drive)
#   client_id: оставить пустым (использует rclone's default — медленно но работает)
#                ИЛИ создать свой через Google Cloud Console для скорости
#   client_secret: пусто
#   scope: 1 (full access) — для чтения хватит 2 (read-only)
#   service_account_file: пусто (используем OAuth)
#   y/n) Auto config: n (на сервере — не на маке)
#   Откроется URL → залогинься на маке → введи code на coo
#   Configure as Shared Drive: n (если это твой личный диск)
#   Confirm: y
#   Quit: q
```

После этого `rclone lsd tairov-gdrive:` должна показать список папок твоего Google Drive.

## Использование

### Базовый запуск

```bash
cd ~/compliance-assistant-repo/tools/gdrive-import
export API_KEY=$(grep API_KEY= /etc/mail-stack/compliance-logic.env | cut -d'=' -f2-)

python3 import_from_gdrive.py \
    --remote tairov-gdrive \
    --folder "Tairov/Архив выписок 2025" \
    --client-inn 050900147847 \
    --doc-type STATEMENT \
    --source BACKFILL \
    --api-key $API_KEY
```

### Dry-run (без реальной загрузки)

```bash
python3 import_from_gdrive.py \
    --remote tairov-gdrive \
    --folder "Tairov/Test" \
    --client-inn 050900147847 \
    --api-key $API_KEY \
    --dry-run
```

Покажет какие файлы будут загружены, но никаких POST'ов не сделает.

### Параметры

| Параметр | Default | Описание |
|---|---|---|
| `--remote` | required | Имя rclone remote (из rclone config) |
| `--folder` | required | Путь к папке на remote (относительно root) |
| `--client-inn` | required | ИНН клиента, должен уже существовать в БД |
| `--doc-type` | STATEMENT | STATEMENT / CONTRACT / ACT / INVOICE / OTHER |
| `--source` | BACKFILL | EMAIL / BACKFILL / MANUAL |
| `--historic` | true | Помечать как historic (Inspector не алертит) |
| `--base-url` | `https://127.0.0.1:8771` | URL compliance-logic |
| `--ca-cert` | `/etc/compliance-tls/ca/ca.crt` | CA для TLS verification |
| `--api-key` | required | X-API-Key |
| `--workers` | 5 | Количество параллельных загрузок |
| `--dry-run` | false | Только показать что будет загружено |
| `--keep-temp` | false | Не удалять /tmp/gdrive-import-* после |

## Идемпотентность

Повторный запуск **безопасен**: compliance-logic возвращает HTTP 409 для дублей (по sha256). Скрипт считает это "skipped", не "failed".

Это позволяет:
1. Запустить раз → большая часть успешно
2. Запустить второй раз → перезагрузит только то что упало в первый раз

## Что считается failed

- HTTP 5xx после 3 retry с exponential backoff (2s, 4s, 8s)
- Connection error / timeout
- HTTP 4xx кроме 409 (например 401 unauthorized, 422 validation)

Первые 10 failures выводятся в отчёте. Полный лог — в stderr.

## Производительность

| Метрика | Типично |
|---|---|
| Скорость скачивания | ~10-50 МБ/с (зависит от GDrive throttle) |
| Скорость загрузки в compliance-logic | ~50-100 файлов/сек на 5 workers |
| RAM | ~100 МБ (поток через файл-handler, без полной буферизации) |
| Storage temp | Размер архива на GDrive |

## Будущие улучшения

См. SECURITY_DEBT.md #19. Пока в SECURITY_DEBT:
- Интеграция с orchestrator (вызов через POST /admin/backfill — DEC-023 v1.5)
- Прогресс trace через ComplianceEvent с trace_id
- Batched upload через `multipart/mixed` (когда compliance-logic поддержит)
- Resume from checkpoint при interruption
