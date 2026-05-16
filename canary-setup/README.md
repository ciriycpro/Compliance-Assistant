# Canary deployment — orchestrator v1.2

Изолированный запуск **orchestrator-v1.2** на coo **рядом с production v1.1** для smoke-теста incremental workflow логики **без риска** сломать production.

## Изоляция

| Что | Production (v1.1) | Canary (v1.2) |
|---|---|---|
| orchestrator-bin | `/opt/mail-stack/orchestrator/` | `/opt/mail-stack/orchestrator-canary/` |
| systemd | `orchestrator.service` :8769 | `orchestrator-canary.service` :8779 |
| state-service | `state-service.service` :8770 | `state-service-canary.service` :8771 |
| Redis DB | `0` (production) | `1` (изолировано) |
| API keys | `/tmp/state_api_key` | `/tmp/state_canary_api_key`, `/tmp/orchestrator_canary_api_key` |
| Env files | `orchestrator.env` | `orchestrator-canary.env`, `state-service-canary.env` |

## Pre-requisites

На coo должны быть:
- Redis работает на :6379
- mail-service, attachment, parser, summary, agent-caller — все active
- production orchestrator + state-service работают на :8769 + :8770

## Шаги

### 1. Распаковка canary-setup на coo

На маке: скачать `canary-setup-v1.2.tar.gz` из чата → `~/Downloads/`. Затем:

```bash
gcloud compute scp ~/Downloads/canary-setup-v1.2.tar.gz coo:~/ --zone=us-west4-a
gcloud compute scp ~/Downloads/orchestrator-v1.2-full.tar.gz coo:~/ --zone=us-west4-a
```

На coo:
```bash
cd ~
tar -xzf canary-setup-v1.2.tar.gz
cd canary-setup
chmod +x *.sh
```

### 2. Установка canary

```bash
bash install-canary.sh ~/orchestrator-v1.2-full.tar.gz
```

Что произойдёт:
1. Создаст `/opt/mail-stack/orchestrator-canary/`
2. Прогонит `go test ./...` — если хоть один FAIL → installer выйдет с ошибкой
3. Соберёт `orchestrator-bin`
4. Создаст оба env-файла с изолированными API keys
5. Установит systemd units (state-service-canary, orchestrator-canary)
6. Стартует оба сервиса
7. Проверит /health на обоих

### 3. Smoke-test

```bash
bash canary-smoke.sh
```

5 сценариев:
1. Первый запуск (state пустой) → fallback 24h + дайджест в Telegram
2. Второй запуск → incremental period («с момента предыдущего обзора»)
3. Двойной клик защищён через lock
4. Trigger=cron → WA pre-alert + Telegram
5. `/state/activate` записывает last_at в Redis DB=1

Скрипт **остановится** между сценариями и попросит подтвердить визуально что Telegram-сообщение пришло.

### 4. Switchover в production

После того как все 5 кейсов прошли + Telegram-сообщения визуально правильные:

```bash
bash switchover.sh
```

Что произойдёт:
1. Бэкап production v1.1 в `orchestrator.bak.v1.1.<date>`
2. Остановка production orchestrator + canary
3. Копирование canary кода в production location
4. Добавление новых env-переменных в `orchestrator.env`
5. Старт production orchestrator (теперь v1.2)
6. Health check

## Rollback

Если что-то сломалось ПОСЛЕ switchover:

```bash
sudo systemctl stop orchestrator
sudo rm -rf /opt/mail-stack/orchestrator
sudo mv /opt/mail-stack/orchestrator.bak.v1.1.<date> /opt/mail-stack/orchestrator
sudo cp /etc/mail-stack/orchestrator.env.bak.v1.1.<date> /etc/mail-stack/orchestrator.env
sudo systemctl start orchestrator
```

Production v1.1 вернётся за ~30 секунд.

## Cleanup canary после switchover (опционально)

```bash
sudo systemctl stop orchestrator-canary state-service-canary
sudo systemctl disable orchestrator-canary state-service-canary
sudo rm /etc/systemd/system/orchestrator-canary.service /etc/systemd/system/state-service-canary.service
sudo rm -rf /opt/mail-stack/orchestrator-canary
sudo rm /etc/mail-stack/orchestrator-canary.env /etc/mail-stack/state-service-canary.env
sudo systemctl daemon-reload
redis-cli -n 1 FLUSHDB
```

## FAQ

**Q: Можно ли запустить canary и production одновременно отправлять дайджесты?**
A: Да. Они работают на разных портах, разных Redis DB и используют общий Telegram chat — будут просто два одинаковых дайджеста параллельно. Не страшно.

**Q: Что если canary падает?**
A: Production не затронется. Логи: `sudo journalctl -u orchestrator-canary -f`.

**Q: Сколько canary должен работать перед switchover?**
A: По решению Артёма — не 24h, а ровно столько, чтобы все 5 smoke кейсов прошли с подтверждениями.
