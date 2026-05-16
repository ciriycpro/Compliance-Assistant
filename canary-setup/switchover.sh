#!/bin/bash
# switchover.sh — переключение production orchestrator с v1.1 на v1.2.
#
# Что делает:
#  1. Останавливает orchestrator-canary (он больше не нужен после switchover)
#  2. Бэкап текущего orchestrator/ (v1.1) → orchestrator-v1.1.bak.<date>
#  3. Копирует код из orchestrator-canary в orchestrator (новый бинарник + код)
#  4. Бэкап env orchestrator.env (v1.1)
#  5. ДОБАВЛЯЕТ в orchestrator.env (НЕ заменяет существующие переменные):
#     - STATE_SERVICE_URL=http://127.0.0.1:8770 (production state-service)
#     - STATE_SERVICE_API_KEY=<production key>
#     - WORKFLOW_LOCK_TTL_SECONDS=300
#     - FALLBACK_PERIOD_HOURS=24
#  6. Рестарт orchestrator
#  7. Health check
#  8. Удаление canary (по желанию)
#
# Запускать ПОСЛЕ canary-smoke.sh когда все 5 тестов прошли + визуальные подтверждения.

set -euo pipefail

echo "==========================================="
echo "  SWITCHOVER — orchestrator v1.1 → v1.2"
echo "==========================================="
echo ""
echo "ВНИМАНИЕ: этот скрипт ЗАМЕНИТ production orchestrator на v1.2."
echo ""
read -p "Уверен что canary тесты прошли? (yes/no) " confirm
if [ "$confirm" != "yes" ]; then
    echo "Отмена switchover."
    exit 1
fi
echo ""

# ============================================================
# 0. Проверка что canary работал недавно (защита от случайного запуска)
# ============================================================
if ! sudo systemctl is-active orchestrator-canary &>/dev/null; then
    echo "WARNING: orchestrator-canary не active. Возможно ты не запустил canary сначала."
    read -p "Всё равно продолжить? (yes/no) " confirm
    if [ "$confirm" != "yes" ]; then
        exit 1
    fi
fi

# ============================================================
# 1. Бэкап production
# ============================================================
DATE=$(date +%Y%m%d-%H%M%S)
echo "=== 1. Бэкап production orchestrator (v1.1) ==="

sudo cp -r /opt/mail-stack/orchestrator /opt/mail-stack/orchestrator.bak.v1.1.$DATE
sudo cp /etc/mail-stack/orchestrator.env /etc/mail-stack/orchestrator.env.bak.v1.1.$DATE
echo "Production бэкап: /opt/mail-stack/orchestrator.bak.v1.1.$DATE"
echo "Env бэкап:        /etc/mail-stack/orchestrator.env.bak.v1.1.$DATE"

# ============================================================
# 2. Stop production orchestrator + canary
# ============================================================
echo ""
echo "=== 2. Останавливаем сервисы ==="
sudo systemctl stop orchestrator
echo "orchestrator (v1.1) остановлен"
sudo systemctl stop orchestrator-canary || true
echo "orchestrator-canary остановлен"

# ============================================================
# 3. Копирование canary → production location
# ============================================================
echo ""
echo "=== 3. Копирование canary кода в production location ==="
sudo rsync -a /opt/mail-stack/orchestrator-canary/ /opt/mail-stack/orchestrator/
sudo chown -R iakshin77:iakshin77 /opt/mail-stack/orchestrator/
echo "Код переписан"

# ============================================================
# 4. Обновляем production env — добавляем v1.2 переменные
# ============================================================
echo ""
echo "=== 4. Обновляем /etc/mail-stack/orchestrator.env ==="

PROD_STATE_KEY=$(cat /tmp/state_api_key)

# Проверяем что переменные ещё не добавлены
if ! sudo grep -q "STATE_SERVICE_URL" /etc/mail-stack/orchestrator.env; then
    echo "
# === v1.2 incremental workflow (DEC-013) ===
STATE_SERVICE_URL=http://127.0.0.1:8770
STATE_SERVICE_API_KEY=$PROD_STATE_KEY
WORKFLOW_LOCK_TTL_SECONDS=300
FALLBACK_PERIOD_HOURS=24
" | sudo tee -a /etc/mail-stack/orchestrator.env > /dev/null
    echo "Добавлены: STATE_SERVICE_URL, STATE_SERVICE_API_KEY, WORKFLOW_LOCK_TTL_SECONDS, FALLBACK_PERIOD_HOURS"
else
    echo "STATE_SERVICE_URL уже в env. Пропускаю добавление."
fi

# ============================================================
# 5. Старт production orchestrator (с новым кодом и env)
# ============================================================
echo ""
echo "=== 5. Старт orchestrator (v1.2) ==="
sudo systemctl start orchestrator
sleep 3
sudo systemctl is-active orchestrator

echo ""
echo "=== Логи стартапа ==="
sudo journalctl -u orchestrator -n 20 --no-pager --since "10 seconds ago"

# ============================================================
# 6. Health check
# ============================================================
echo ""
echo "=== 6. Health check ==="
curl -s http://127.0.0.1:8769/health
echo ""

# ============================================================
# 7. Опциональное удаление canary (можно оставить если хочешь)
# ============================================================
echo ""
echo "==========================================="
echo "  SWITCHOVER DONE"
echo "==========================================="
echo ""
echo "Production orchestrator теперь v1.2 на :8769"
echo ""
echo "Canary остался запущен на :8779 (state-service-canary на :8771)"
echo "Если canary больше не нужен, удали:"
echo ""
echo "  sudo systemctl stop orchestrator-canary state-service-canary"
echo "  sudo systemctl disable orchestrator-canary state-service-canary"
echo "  sudo rm /etc/systemd/system/orchestrator-canary.service"
echo "  sudo rm /etc/systemd/system/state-service-canary.service"
echo "  sudo rm -rf /opt/mail-stack/orchestrator-canary"
echo "  sudo rm /etc/mail-stack/orchestrator-canary.env /etc/mail-stack/state-service-canary.env"
echo "  sudo systemctl daemon-reload"
echo "  redis-cli -n 1 FLUSHDB  # очистка canary state из Redis"
echo ""
echo "Бэкап v1.1 сохранён: /opt/mail-stack/orchestrator.bak.v1.1.$DATE"
echo "Если что-то пошло не так — rollback:"
echo "  sudo systemctl stop orchestrator"
echo "  sudo rm -rf /opt/mail-stack/orchestrator"
echo "  sudo mv /opt/mail-stack/orchestrator.bak.v1.1.$DATE /opt/mail-stack/orchestrator"
echo "  sudo cp /etc/mail-stack/orchestrator.env.bak.v1.1.$DATE /etc/mail-stack/orchestrator.env"
echo "  sudo systemctl start orchestrator"
