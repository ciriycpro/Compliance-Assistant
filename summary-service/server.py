"""
Summary Service v1 — по DEC-009.

Принимает массив писем за период с распарсенными вложениями.
Возвращает дайджест в двух форматах: markdown (для Sheets) + telegram-text.

Primary: Claude Haiku 4.5 через OpenRouter.
Fallback: DeepSeek V3 при rate-limit или ошибках Haiku.
"""

import os
import json
import logging
from datetime import datetime, timezone
from typing import Optional, Any

import httpx
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

# === Конфиг через env (docker-friendly по DEC-007) ===
OPENROUTER_API_KEY = os.getenv('OPENROUTER_API_KEY')
SUMMARY_MODEL_PRIMARY = os.getenv('SUMMARY_MODEL_PRIMARY', 'anthropic/claude-haiku-4.5')
SUMMARY_MODEL_FALLBACK = os.getenv('SUMMARY_MODEL_FALLBACK', 'deepseek/deepseek-chat')
SUMMARY_TEMPERATURE = float(os.getenv('SUMMARY_TEMPERATURE', '0.3'))
SUMMARY_MAX_TOKENS = int(os.getenv('SUMMARY_MAX_TOKENS', '4000'))
OPENROUTER_TIMEOUT_SEC = int(os.getenv('OPENROUTER_TIMEOUT_SEC', '120'))
OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions"

# === Системный промпт из DEC-009 ===
SYSTEM_PROMPT = """<role>
Ты — персональный AI-ассистент Артёма Якшина (ИП Таиров — его клиент).
Ты общаешься с Артёмом каждый день, как помощник, который сам прошёл по почте 
и докладывает ситуацию. Тон — деловой друг, не казённый.
</role>

<task>
Тебе на вход дан массив писем за период с распарсенными вложениями.
Каждое вложение УЖЕ обработано системой: поле `parsed_attachments[].text` содержит 
извлечённый текст документа или описание изображения.

ВАЖНО (DEC-0030): для длинных документов также может присутствовать поле 
`parsed_attachments[].distill_result` — это семантический дистиллят документа 
от Claude Haiku (map-reduce). Если distill_result есть — ПРЕДПОЧИТАЙ его 
полному тексту: оттуда бери document_type, summary_brief, key_facts, 
requirements, parties, amounts, dates. Дистиллят уже выделил суть документа 
лучше чем сырой текст. Используй raw text только если distill_result отсутствует.

Структура distill_result (если есть):
- document_type: тип документа (bank_response, tax_declaration, contract, act,
  statement, letter, notice, report, other)
- summary_brief: 1-2 предложения сути документа
- key_facts: список 3-7 ключевых фактов
- requirements: что требуется от получателя письма
- parties: основные стороны с ИНН
- amounts, dates: ключевые суммы и даты
- confidence: уверенность дистилляции (0-1)

Твоя задача — рассказать Артёму что в почте, своими словами. Не "дайджест", не 
"сводка", не "перечень" — а живой доклад от помощника.
</task>

<structure>
Начни с короткого приветствия, которое варьируется. Не одинаковая фраза каждый день.
Примеры (рандомизируй стиль, не копируй дословно):
- "Привет, прошёлся по почте — вот что нашёл..."
- "Привет, на почте за период такая картина..."
- "Доброе утро, разобрал всё что пришло. Главное по делу:"
- "Привет, в почте за период X писем. Из важного:"

Дальше — основное содержимое, без жёстких заголовков-капителей. Можно использовать 
эмодзи как маркеры разделов, но без слов "Сводка", "Раздел", "Блок".

⚠️ Что требует действий — конкретные пункты с отправителем, сутью, сроком и 
рекомендацией. Это главное. Пиши живо: "ФНС хочет выписки до 20 мая — надо 
подготовить квартальные за Q1" вместо "Требование ФНС о предоставлении документов".

📎 Что во вложениях (если есть) — описывай по-человечески:
- Документы: "договор от Альфы на 45к, ИП Петров подписан, нам — нет ещё"
- Фото: опиши что видишь напрямую (люди, объекты, текст). Если фото явно 
  не рабочее — добавь "(не по делу)" и опиши как есть, без эвфемизмов
- Таблицы: "выписка из 1С за апрель, ~200 строк, основные суммы по клиенту Бета"

📌 Информационное — одной строкой каждое, если их 1-3. Если больше — сгруппируй.
"От Контур.Экстерн — 3 уведомления, ничего срочного"

🗑 Спам — одной строкой числом, без перечисления.
"Спам: 2 письма с рекламой"

В конце — короткий итог одной фразой если уместно. Например: "Главное на сегодня 
— ФНС и Альфа, остальное может подождать." Не обязательно если день спокойный.
</structure>

<format>
Ответь строго в JSON:
{
  "summary_markdown": "<развёрнутая версия в Sheets с минимальной markdown-разметкой>",
  "summary_telegram": "<разговорная версия для Telegram, не более 1000 символов>"
}

summary_telegram должен:
- Звучать как живой текст помощника, не как корпоративный отчёт
- Использовать эмодзи естественно (⚠️ перед важным, 📎 перед вложениями, 📌 перед 
  информационным, 🗑 перед спамом)
- Уместиться в 1000 символов — если переполняется, сокращай информационные/спам, 
  оставляй критичное
- Не содержать markdown-разметки (нет #, нет **, нет ##)
- Не быть бюрократическим

summary_markdown — то же содержание, чуть подробнее, можно использовать **жирный** 
для отправителей, ## для приветствия. Тон такой же живой.
</format>

<example>
Пример summary_telegram (на 3 письма):

Привет, прошёлся по почте за вчера — там немного, но есть важное.

⚠️ Главное:
- ООО Альфа выставил счёт №247 на 45к, оплата до 18.05. В банк-клиент.
- ФНС хочет выписки за Q1 до 20 мая. Надо подготовить, в Экстерне всё есть.

📎 Во вложениях:
- "Счёт №247.pdf" — счёт от Альфы за монтаж, ИНН 7712345678, с печатью.
- "trebovanie.pdf" — требование ФНС, ссылается на ст. 93 НК.
- "Рисунок (31).jpg" — фото судебного определения по делу №2а-245/2026 
  Ахтынского суда.

📌 Контур.Экстерн прислал уведомление про квартальный отчёт.

Главное на сегодня — Альфа и ФНС, остальное по фону.

---

Пример summary_telegram (на спокойный день, 2 информационных письма):

Привет, в почте за сегодня тишина. Два уведомления от Контур.Экстерн — про 
обновления в системе. Никаких срочных дел, можешь спокойно работать.
</example>

<rules>
- Не выдумывай факты. Если не понимаешь — пиши "тут не ясно, надо посмотреть глазами".
- Не цензурируй описание изображений. Описывай объективно, как видишь.
- Используй человеческий язык: "Альфа выставила счёт" а не "от ООО Альфа поступил 
  счёт-фактура". "ФНС хочет" а не "ФНС истребует".
- Имя клиента в обращении — Артём (или просто "Привет" без имени), не "Уважаемый".
- Числа округляй для устной речи: 45000 → "45к", 1500000 → "1.5 миллиона".
- Действия описывай глаголами: "оплатить", "подготовить", "ответить", а не 
  "осуществить оплату".
- Если фото на тему природы, личной жизни, искусства — описывай прямо, например 
  "пейзаж с морем", "портрет женщины", без эвфемизмов и без оценок.
- summary_telegram строго до 1000 символов. Приоритет: критичное действие → важные 
  вложения → информационное → спам.
- Ответ — ТОЛЬКО валидный JSON, без преамбулы и markdown-обёртки на JSON.
</rules>"""


# === Логирование ===
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s [%(levelname)s] %(message)s',
)
log = logging.getLogger("summary-service")


# === Модели request/response ===
class ParsedAttachment(BaseModel):
    text: str
    method: Optional[str] = None
    format: Optional[str] = None
    warnings: list[str] = []
    # DEC-0030: дистиллят от summary-prep для длинных документов. Опциональный.
    # Если присутствует — LLM использует его summary_brief/key_facts/requirements
    # вместо raw text. См. SYSTEM_PROMPT.
    distill_result: Optional[dict[str, Any]] = None


class MessageInput(BaseModel):
    from_field: Optional[str] = None
    email: Optional[str] = None
    fio: Optional[str] = None
    subject: Optional[str] = None
    date: Optional[str] = None
    body_text: Optional[str] = None
    attachment_names: list[str] = []
    parsed_attachments: list[ParsedAttachment] = []

    class Config:
        # Принимаем как 'from' так и 'from_field' (from — зарезервированное слово в Python)
        populate_by_name = True
        fields = {'from_field': 'from'}


class SummaryRequest(BaseModel):
    period: str  # "2026-05-13" или "2026-05-13T09:00 to 2026-05-13T15:00"
    messages: list[dict[str, Any]]  # принимаем raw dict из mail-service


class SummaryResponse(BaseModel):
    summary_markdown: str
    summary_telegram: str
    tokens_in: int
    tokens_out: int
    cost_usd: float
    model: str
    fallback_used: bool = False


# === Цены моделей (USD per million tokens) ===
PRICING = {
    'anthropic/claude-haiku-4.5': {'in': 0.80, 'out': 4.00},
    'deepseek/deepseek-chat': {'in': 0.27, 'out': 1.10},
}


def estimate_cost(model: str, tokens_in: int, tokens_out: int) -> float:
    p = PRICING.get(model, {'in': 0.0, 'out': 0.0})
    return round((tokens_in * p['in'] + tokens_out * p['out']) / 1_000_000, 5)


# === LLM call через OpenRouter ===
def call_openrouter(model: str, messages_payload: list, temperature: float = SUMMARY_TEMPERATURE) -> tuple[str, dict]:
    """Возвращает (content, usage)."""
    if not OPENROUTER_API_KEY:
        raise HTTPException(
            status_code=500,
            detail="OPENROUTER_API_KEY not configured",
        )

    payload = {
        "model": model,
        "messages": messages_payload,
        "temperature": temperature,
        "max_tokens": SUMMARY_MAX_TOKENS,
        "response_format": {"type": "json_object"},
    }

    headers = {
        "Authorization": f"Bearer {OPENROUTER_API_KEY}",
        "Content-Type": "application/json",
    }

    with httpx.Client(timeout=OPENROUTER_TIMEOUT_SEC) as client:
        r = client.post(OPENROUTER_URL, json=payload, headers=headers)
        if r.status_code != 200:
            raise httpx.HTTPStatusError(
                f"OpenRouter returned {r.status_code}: {r.text[:300]}",
                request=r.request, response=r,
            )
        data = r.json()
        content = data["choices"][0]["message"]["content"]
        usage = data.get("usage", {})
        return content, usage


def format_user_message(period: str, messages: list[dict]) -> str:
    """Готовит user message для LLM из массива писем.

    DEC-0030 failsafe: если у вложения есть distill_result — не отправляем
    полный raw text в LLM (это удваивало бы input tokens и могло вернуть
    context overflow). Оставляем первые 1500 chars как backup-контекст.
    Orchestrator уже обнуляет att.Text после дистилляции, но этот код —
    второй уровень защиты от context overflow.
    """
    TEXT_KEEP_AFTER_DISTILL = 1500
    compacted_messages = []
    for msg in messages:
        msg_copy = dict(msg)
        atts = msg_copy.get("parsed_attachments") or []
        new_atts = []
        for att in atts:
            att_copy = dict(att)
            if att_copy.get("distill_result"):
                text = att_copy.get("text") or ""
                if len(text) > TEXT_KEEP_AFTER_DISTILL:
                    att_copy["text"] = text[:TEXT_KEEP_AFTER_DISTILL] + "...[full text replaced by distill_result]"
            new_atts.append(att_copy)
        msg_copy["parsed_attachments"] = new_atts
        compacted_messages.append(msg_copy)

    return json.dumps({
        "period": period,
        "messages": compacted_messages,
    }, ensure_ascii=False, indent=2)


def parse_llm_json_response(content: str) -> dict:
    """Парсит JSON-ответ от LLM, с очисткой от потенциальных markdown-обёрток."""
    content = content.strip()
    # Убираем markdown code fence если есть
    if content.startswith("```"):
        lines = content.split("\n")
        content = "\n".join(lines[1:-1] if lines[-1].strip() == "```" else lines[1:])
    if content.startswith("json"):
        content = content[4:].lstrip()
    return json.loads(content)


# === FastAPI app ===
app = FastAPI(title="summary-service-v1")


@app.get("/health")
def health():
    return {
        "status": "ok",
        "service": "summary-service-v1",
        "openrouter_configured": bool(OPENROUTER_API_KEY),
        "model_primary": SUMMARY_MODEL_PRIMARY,
        "model_fallback": SUMMARY_MODEL_FALLBACK,
        "temperature": SUMMARY_TEMPERATURE,
        "max_tokens": SUMMARY_MAX_TOKENS,
    }


@app.post("/summary", response_model=SummaryResponse)
def summary(req: SummaryRequest):
    """
    Генерация дайджеста из массива писем.

    Алгоритм:
    1. Подготовка JSON-payload для LLM
    2. Попытка Primary (Claude Haiku 4.5)
    3. При ошибке/rate-limit — Fallback (DeepSeek V3)
    4. Парсинг JSON-ответа, возврат с метриками
    """
    log.info(f"Summary request: period={req.period}, messages={len(req.messages)}")

    user_msg = format_user_message(req.period, req.messages)
    messages_payload = [
        {"role": "system", "content": SYSTEM_PROMPT},
        {"role": "user", "content": user_msg},
    ]

    fallback_used = False
    model_used = SUMMARY_MODEL_PRIMARY

    # Primary
    try:
        content, usage = call_openrouter(SUMMARY_MODEL_PRIMARY, messages_payload)
        log.info(f"Primary ({SUMMARY_MODEL_PRIMARY}) success, tokens={usage}")
    except (httpx.HTTPStatusError, httpx.RequestError, Exception) as e:
        log.warning(f"Primary failed: {type(e).__name__}: {str(e)[:200]}, switching to fallback")
        fallback_used = True
        model_used = SUMMARY_MODEL_FALLBACK
        try:
            content, usage = call_openrouter(SUMMARY_MODEL_FALLBACK, messages_payload)
            log.info(f"Fallback ({SUMMARY_MODEL_FALLBACK}) success, tokens={usage}")
        except Exception as e2:
            log.error(f"Fallback also failed: {type(e2).__name__}: {str(e2)[:200]}")
            raise HTTPException(
                status_code=502,
                detail={
                    "error": "all_providers_failed",
                    "primary_error": str(e)[:200],
                    "fallback_error": str(e2)[:200],
                },
            )

    # Парсинг JSON
    try:
        result = parse_llm_json_response(content)
    except json.JSONDecodeError as e:
        log.error(f"Failed to parse LLM JSON: {e}, content={content[:500]}")
        raise HTTPException(
            status_code=500,
            detail={
                "error": "llm_returned_invalid_json",
                "raw_content_preview": content[:500],
            },
        )

    tokens_in = usage.get("prompt_tokens", 0)
    tokens_out = usage.get("completion_tokens", 0)
    cost = estimate_cost(model_used, tokens_in, tokens_out)

    return SummaryResponse(
        summary_markdown=result.get("summary_markdown", ""),
        summary_telegram=result.get("summary_telegram", ""),
        tokens_in=tokens_in,
        tokens_out=tokens_out,
        cost_usd=cost,
        model=model_used,
        fallback_used=fallback_used,
    )
