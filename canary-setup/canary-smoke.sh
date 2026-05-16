#!/bin/bash
# canary-smoke.sh — smoke-test для orchestrator-v1.2 canary.
#
# Запускается ПОСЛЕ того как state-service-canary и orchestrator-canary запущены.
# Проверяет 5 ключевых сценариев incremental workflow.
#
# Использование:
#   bash canary-smoke.sh
#
# Все state-операции идут в Redis DB=1 (изолировано от production).
# Доставка идёт в реальный Telegram chat Артёма (249979054).

set -u  # запрещаем неинициализированные переменные

CANARY_API_KEY=$(cat /tmp/orchestrator_canary_api_key 2>/dev/null || echo "MISSING")
CANARY_STATE_API_KEY=$(cat /tmp/state_canary_api_key 2>/dev/null || echo "MISSING")
ORCH_URL="http://127.0.0.1:8779"
STATE_URL="http://127.0.0.1:8771"
CHAT_ID="249979054"

if [ "$CANARY_API_KEY" = "MISSING" ] || [ "$CANARY_STATE_API_KEY" = "MISSING" ]; then
    echo "ERROR: API keys не найдены в /tmp/. Запусти install-canary.sh сначала."
    exit 1
fi

PASS=0
FAIL=0

check() {
    local label="$1"
    local expected="$2"
    local actual="$3"
    if echo "$actual" | grep -q "$expected"; then
        echo "  ✅ $label"
        PASS=$((PASS+1))
    else
        echo "  ❌ $label"
        echo "     ожидали: $expected"
        echo "     получили: $actual"
        FAIL=$((FAIL+1))
    fi
}

echo "==========================================="
echo "  CANARY SMOKE TEST — orchestrator v1.2"
echo "==========================================="
echo ""

# ============================================================
# Подготовка: очищаем canary state для chat_id Артёма
# ============================================================
echo "=== Подготовка: чистим Redis DB=1 для chat $CHAT_ID ==="
redis-cli -n 1 DEL "state:${CHAT_ID}:last_at" "state:${CHAT_ID}:lock" > /dev/null
echo "DB=1 namespace cleared"
echo ""

# ============================================================
# 0. Базовая проверка сервисов up
# ============================================================
echo "=== 0. Сервисы up? ==="
HEALTH_STATE=$(curl -s -o /dev/null -w "%{http_code}" $STATE_URL/health)
HEALTH_ORCH=$(curl -s -o /dev/null -w "%{http_code}" $ORCH_URL/health)
check "state-service-canary :8771 /health" "200" "$HEALTH_STATE"
check "orchestrator-canary :8779 /health" "200" "$HEALTH_ORCH"
echo ""

# ============================================================
# 1. /digest-now первый запуск — fallback period
# ============================================================
echo "=== 1. Первый запуск (state пустой) → должен быть fallback 24h ==="
echo "→ POST /digest-now {trigger:button}"
RESPONSE=$(curl -s -X POST $ORCH_URL/digest-now \
    -H "X-API-Key: $CANARY_API_KEY" \
    -H "Content-Type: application/json" \
    -d "{\"trigger\":\"button\",\"chat_id\":\"$CHAT_ID\"}")
echo "Ответ: $RESPONSE"
check "Получили trace_id" "trace_id" "$RESPONSE"
check "Status accepted" "accepted" "$RESPONSE"

echo ""
echo "Ждём 90 секунд пока workflow дойдёт до конца..."
sleep 90

echo ""
echo "=== Логи canary orchestrator (последние 60 строк) ==="
sudo journalctl -u orchestrator-canary -n 60 --no-pager --since "100 seconds ago" | tail -30

echo ""
echo "=== State после первого запуска ==="
STATUS=$(curl -s $STATE_URL/state/$CHAT_ID/status -H "X-API-Key: $CANARY_STATE_API_KEY")
echo "Status: $STATUS"
check "last_at установлен (не null)" "last_at" "$STATUS"
check "Lock снят (locked:false)" "locked\":false" "$STATUS"

echo ""
echo "👉 ПРОВЕРЬ В TELEGRAM: пришёл ли дайджест? Период должен быть 'за последние 24 часа' (fallback)"
echo ""
read -p "Нажми Enter после того как проверил Telegram (или Ctrl+C для прерывания)..."

# ============================================================
# 2. /digest-now второй запуск — incremental period
# ============================================================
echo ""
echo "=== 2. Второй запуск (state есть) → incremental ==="
echo "→ POST /digest-now {trigger:button}"
RESPONSE=$(curl -s -X POST $ORCH_URL/digest-now \
    -H "X-API-Key: $CANARY_API_KEY" \
    -H "Content-Type: application/json" \
    -d "{\"trigger\":\"button\",\"chat_id\":\"$CHAT_ID\"}")
echo "Ответ: $RESPONSE"

echo "Ждём 90 секунд..."
sleep 90

echo ""
echo "=== Логи: period.incremental должен быть в логах ==="
sudo journalctl -u orchestrator-canary -n 30 --no-pager --since "100 seconds ago" | grep -E "period|fetch_mail|workflow.done" | head -10

echo ""
echo "👉 ПРОВЕРЬ В TELEGRAM: дайджест должен быть короче (период несколько минут)"
echo "    Заголовок должен включать 'с момента предыдущего обзора'"
echo ""
read -p "Нажми Enter после проверки..."

# ============================================================
# 3. Двойной клик — защита через lock
# ============================================================
echo ""
echo "=== 3. Двойной клик защищён через lock ==="

# Сначала чистим state и lock
redis-cli -n 1 DEL "state:${CHAT_ID}:lock" > /dev/null

echo "→ POST /digest-now (первый запрос)..."
RESP1=$(curl -s -X POST $ORCH_URL/digest-now \
    -H "X-API-Key: $CANARY_API_KEY" \
    -H "Content-Type: application/json" \
    -d "{\"trigger\":\"button\",\"chat_id\":\"$CHAT_ID\"}")
echo "Ответ 1: $RESP1"

# Немедленно второй запрос (без задержки)
echo "→ POST /digest-now (второй запрос немедленно)..."
RESP2=$(curl -s -X POST $ORCH_URL/digest-now \
    -H "X-API-Key: $CANARY_API_KEY" \
    -H "Content-Type: application/json" \
    -d "{\"trigger\":\"button\",\"chat_id\":\"$CHAT_ID\"}")
echo "Ответ 2: $RESP2"

# Проверка lock через несколько секунд
sleep 3
echo ""
echo "=== Status после 3 секунд ==="
STATUS=$(curl -s $STATE_URL/state/$CHAT_ID/status -H "X-API-Key: $CANARY_STATE_API_KEY")
echo "Status: $STATUS"
check "Lock активен (locked:true)" "locked\":true" "$STATUS"

echo ""
echo "=== Логи: должны видеть 'skipped.lock_held' ==="
sudo journalctl -u orchestrator-canary -n 50 --no-pager --since "10 seconds ago" | grep -E "lock|skipped" | tail -5

echo ""
echo "Ждём 90 секунд до завершения первого workflow..."
sleep 90

echo ""
echo "=== Logs: первый завершился, lock снят ==="
STATUS=$(curl -s $STATE_URL/state/$CHAT_ID/status -H "X-API-Key: $CANARY_STATE_API_KEY")
echo "Status final: $STATUS"
check "Lock снят" "locked\":false" "$STATUS"

echo ""
echo "👉 В TELEGRAM должен прийти ОДИН дайджест (не два — второй заблокирован)"
echo ""
read -p "Нажми Enter после проверки..."

# ============================================================
# 4. /digest-now с trigger=cron — WA + Telegram
# ============================================================
echo ""
echo "=== 4. Trigger=cron → должен послать WA pre-alert + Telegram ==="
RESPONSE=$(curl -s -X POST $ORCH_URL/digest-now \
    -H "X-API-Key: $CANARY_API_KEY" \
    -H "Content-Type: application/json" \
    -d "{\"trigger\":\"cron\",\"chat_id\":\"$CHAT_ID\"}")
echo "Ответ: $RESPONSE"

echo ""
echo "Ждём 150 секунд (WA cold start)..."
sleep 150

echo ""
echo "=== Логи: wa_ping.done должен быть в логах ==="
sudo journalctl -u orchestrator-canary -n 50 --no-pager --since "180 seconds ago" | grep -E "wa_ping|deliver" | tail -10

echo ""
echo "👉 ПРОВЕРЬ: на твой WhatsApp (79266143959) пришло уведомление?"
echo "    + в Telegram пришёл дайджест?"
echo ""
read -p "Нажми Enter после проверки..."

# ============================================================
# 5. /state/activate endpoint
# ============================================================
echo ""
echo "=== 5. /state/activate — новый endpoint для Agent Caller ==="
NEW_CHAT="111222333"
echo "→ POST /state/activate {chat_id: $NEW_CHAT}"
ACTIVATE=$(curl -s -X POST $ORCH_URL/state/activate \
    -H "X-API-Key: $CANARY_API_KEY" \
    -H "Content-Type: application/json" \
    -d "{\"chat_id\":\"$NEW_CHAT\"}")
echo "Ответ: $ACTIVATE"
check "Status activated" "activated" "$ACTIVATE"
check "Chat ID правильный" "$NEW_CHAT" "$ACTIVATE"

echo ""
echo "=== Проверка: в Redis DB=1 last_at для $NEW_CHAT записан ==="
REDIS_VAL=$(redis-cli -n 1 GET "state:${NEW_CHAT}:last_at")
echo "Redis значение: $REDIS_VAL"
check "Timestamp в Redis записан" "2026" "$REDIS_VAL"

# Очистка
redis-cli -n 1 DEL "state:${NEW_CHAT}:last_at" > /dev/null

# ============================================================
# Итог
# ============================================================
echo ""
echo "==========================================="
echo "  ИТОГ"
echo "==========================================="
echo "  ✅ PASS: $PASS"
echo "  ❌ FAIL: $FAIL"
echo ""

if [ "$FAIL" -gt 0 ]; then
    echo "ЕСТЬ ОШИБКИ — НЕ ВЫПУСКАТЬ В PRODUCTION."
    exit 1
fi

echo "Все автоматические проверки прошли."
echo ""
echo "Финальный чек-лист (визуальный, требует подтверждения):"
echo "  [ ] В Telegram пришёл дайджест fallback (первый запуск)"
echo "  [ ] В Telegram пришёл дайджест incremental (короткий период)"
echo "  [ ] Второй клик НЕ прислал дубликат"
echo "  [ ] На trigger=cron пришёл WA pre-alert"
echo ""
echo "Если все галочки — готов к switchover (см. canary-switchover.sh)"
