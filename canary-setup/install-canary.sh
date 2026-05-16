#!/bin/bash
# install-canary.sh — установка canary-окружения orchestrator-v1.2.
#
# Что делает:
#  1. Создаёт каталог /opt/mail-stack/orchestrator-canary/
#  2. Распаковывает orchestrator-v1.2-full.tar.gz туда (а НЕ в /opt/mail-stack/orchestrator/)
#  3. Прогоняет go test ./... — если хоть один FAIL → exit 1
#  4. Собирает orchestrator-bin
#  5. Генерирует API key для canary
#  6. Создаёт /etc/mail-stack/orchestrator-canary.env с Redis DB=1, порт 8779
#  7. Создаёт /etc/mail-stack/state-service-canary.env с Redis DB=1, порт 8771
#  8. Устанавливает systemd units, стартует оба сервиса
#  9. Проверяет /health на обоих

set -euo pipefail

# Тарбол с новой версией orchestrator (тот что я отдаю)
ORCHESTRATOR_TARBALL="${1:-$HOME/orchestrator-v1.2-full.tar.gz}"

if [ ! -f "$ORCHESTRATOR_TARBALL" ]; then
    echo "ERROR: $ORCHESTRATOR_TARBALL not found"
    echo "Usage: $0 <path-to-orchestrator-v1.2-full.tar.gz>"
    exit 1
fi

echo "==========================================="
echo "  CANARY INSTALL — orchestrator v1.2"
echo "==========================================="
echo ""

# ============================================================
# 1. Каталог orchestrator-canary
# ============================================================
echo "=== 1. Каталог /opt/mail-stack/orchestrator-canary/ ==="
sudo mkdir -p /opt/mail-stack/orchestrator-canary
sudo chown iakshin77:iakshin77 /opt/mail-stack/orchestrator-canary

# ============================================================
# 2. Распаковка
# ============================================================
echo ""
echo "=== 2. Распаковка $ORCHESTRATOR_TARBALL ==="
cd /opt/mail-stack/orchestrator-canary
tar -xzf "$ORCHESTRATOR_TARBALL"
ls -la

# ============================================================
# 3. Go тесты
# ============================================================
echo ""
echo "=== 3. Зависимости + тесты ==="
go mod tidy
echo ""
echo "Прогон go test ./..."
if ! go test ./... 2>&1 | tail -30; then
    echo ""
    echo "❌ ТЕСТЫ FAIL — installation aborted"
    exit 1
fi
echo ""
echo "✅ Все тесты прошли"

# ============================================================
# 4. Сборка
# ============================================================
echo ""
echo "=== 4. Сборка orchestrator-bin ==="
CGO_ENABLED=0 go build -ldflags="-s -w" -o orchestrator-bin ./cmd/orchestrator
ls -la orchestrator-bin

# ============================================================
# 5. API keys
# ============================================================
echo ""
echo "=== 5. Генерация API keys для canary ==="

# Orchestrator canary API key
CANARY_ORCH_KEY=$(openssl rand -hex 32)
echo "$CANARY_ORCH_KEY" > /tmp/orchestrator_canary_api_key
chmod 600 /tmp/orchestrator_canary_api_key

# State canary API key (отдельный для изоляции)
CANARY_STATE_KEY=$(openssl rand -hex 32)
echo "$CANARY_STATE_KEY" > /tmp/state_canary_api_key
chmod 600 /tmp/state_canary_api_key

echo "Orchestrator canary key: ${CANARY_ORCH_KEY:0:16}..."
echo "State canary key:        ${CANARY_STATE_KEY:0:16}..."
echo "(Сохранены в /tmp/orchestrator_canary_api_key и /tmp/state_canary_api_key)"

# ============================================================
# 6. state-service-canary.env (Redis DB=1, port 8771)
# ============================================================
echo ""
echo "=== 6. state-service-canary env (Redis DB=1, port 8771) ==="
sudo tee /etc/mail-stack/state-service-canary.env > /dev/null << EOF
# state-service CANARY (v1.2 canary)
STATE_SERVICE_HTTP_HOST=127.0.0.1
STATE_SERVICE_HTTP_PORT=8771

# Используем Redis DB=1 (изолированно от production DB=0)
REDIS_ADDR=127.0.0.1:6379
REDIS_PASSWORD=
REDIS_DB=1
REDIS_TIMEOUT=5s

STATE_SERVICE_API_KEY=$CANARY_STATE_KEY
STATE_SERVICE_DEFAULT_LOCK_TTL=300s
STATE_SERVICE_LOG_API_KEY_PREFIX=false
EOF
sudo chmod 600 /etc/mail-stack/state-service-canary.env
sudo chown root:root /etc/mail-stack/state-service-canary.env

# ============================================================
# 7. orchestrator-canary.env
# ============================================================
echo ""
echo "=== 7. orchestrator-canary env (port 8779) ==="

# Берём существующие настройки из production env (chat_id, mail-stack URLs, etc.)
# Только меняем порт и URL state-service на canary.

# Читаем production env для копирования базовых значений
PROD_ENV="/etc/mail-stack/orchestrator.env"
TG_CHAT_ID=$(sudo grep "^TELEGRAM_CHAT_ID=" $PROD_ENV | cut -d= -f2)
WA_NUMBER=$(sudo grep "^WHATSAPP_NUMBER=" $PROD_ENV | cut -d= -f2 || echo "")
SHEETS_ID=$(sudo grep "^SHEETS_ID=" $PROD_ENV | cut -d= -f2 || echo "13SMWzIiwDVRc1eYKJcGm1a-R__7Hbc1hvChTXvZhfsg")

sudo tee /etc/mail-stack/orchestrator-canary.env > /dev/null << EOF
# orchestrator CANARY v1.2 — отдельный экземпляр на порту 8779
# Использует state-service на порту 8771 (Redis DB=1).
# Production orchestrator остаётся на 8769 и использует state-service на 8770 (DB=0).

ORCHESTRATOR_HTTP_HOST=127.0.0.1
ORCHESTRATOR_HTTP_PORT=8779

# Cron отключён в canary (триггер только через POST /digest-now)
ORCHESTRATOR_SCHEDULE=

ORCHESTRATOR_API_KEY=$CANARY_ORCH_KEY

# Mail-stack сервисы — общие с production
MAIL_SERVICE_URL=http://127.0.0.1:8765
ATTACHMENT_SERVICE_URL=http://127.0.0.1:8766
PARSER_SERVICE_URL=http://127.0.0.1:8767
SUMMARY_SERVICE_URL=http://127.0.0.1:8768
SERVICE_TIMEOUT_SEC=180

# state-service: используем CANARY экземпляр на 8771 (Redis DB=1)
STATE_SERVICE_URL=http://127.0.0.1:8771
STATE_SERVICE_API_KEY=$CANARY_STATE_KEY
WORKFLOW_LOCK_TTL_SECONDS=300
FALLBACK_PERIOD_HOURS=24

# Agent Caller — общий (доставка в реальный Telegram, твой chat)
AGENT_CALLER_URL=http://127.0.0.1:3000
TELEGRAM_CHAT_ID=$TG_CHAT_ID
WHATSAPP_NUMBER=$WA_NUMBER

# Sheets (не используется в v1.2)
SHEETS_ID=$SHEETS_ID
SHEETS_RANGE=Дайджест!A:E

# Rate limits
RATE_LIMIT_DIGEST_NOW=60
RATE_LIMIT_CHECK_MAIL=30
DEFAULT_PERIOD_HOURS=24
EOF
sudo chmod 600 /etc/mail-stack/orchestrator-canary.env
sudo chown root:root /etc/mail-stack/orchestrator-canary.env

# ============================================================
# 8. systemd units
# ============================================================
echo ""
echo "=== 8. systemd units ==="

# Копируем .service файлы из текущего каталога (или из тарбола)
CANARY_SETUP_DIR="$(dirname "$0")"
if [ -f "$CANARY_SETUP_DIR/state-service-canary.service" ]; then
    sudo cp "$CANARY_SETUP_DIR/state-service-canary.service" /etc/systemd/system/
    sudo cp "$CANARY_SETUP_DIR/orchestrator-canary.service" /etc/systemd/system/
else
    echo "ERROR: .service files not found in $CANARY_SETUP_DIR"
    exit 1
fi

sudo systemctl daemon-reload

echo ""
echo "Стартуем state-service-canary..."
sudo systemctl enable state-service-canary
sudo systemctl restart state-service-canary
sleep 2
sudo systemctl is-active state-service-canary

echo ""
echo "Стартуем orchestrator-canary..."
sudo systemctl enable orchestrator-canary
sudo systemctl restart orchestrator-canary
sleep 3
sudo systemctl is-active orchestrator-canary

# ============================================================
# 9. Health checks
# ============================================================
echo ""
echo "=== 9. Health checks ==="

echo ""
echo "state-service-canary /health:"
curl -s http://127.0.0.1:8771/health || echo "FAILED"
echo ""

echo ""
echo "orchestrator-canary /health:"
curl -s http://127.0.0.1:8779/health || echo "FAILED"
echo ""

# ============================================================
# Final status
# ============================================================
echo ""
echo "==========================================="
echo "  CANARY INSTALL DONE"
echo "==========================================="
echo ""
echo "Запущено:"
echo "  - state-service-canary на :8771 (Redis DB=1)"
echo "  - orchestrator-canary на :8779"
echo ""
echo "Production остаётся работать:"
echo "  - state-service        на :8770 (Redis DB=0)"
echo "  - orchestrator         на :8769"
echo ""
echo "Запускай smoke-test: bash canary-smoke.sh"
echo ""
echo "RAM использование:"
ps aux | grep -E "(state-service|orchestrator)-bin" | grep -v grep | awk '{print $11, "RSS:", $6/1024, "MB"}'
