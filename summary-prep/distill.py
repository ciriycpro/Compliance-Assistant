"""
summary-prep distill (DEC-0030).

Map-reduce дистилляция длинных документов через Claude Haiku 4.5 via OpenRouter.

Stages:
  1. Чанкование — text → chunks по DISTILL_CHUNK_CHARS (с границей по \\n\\n / \\n / . / space)
  2. MAP    — параллельно (asyncio.gather) каждый chunk → LLM → DistillChunkResult JSON
  3. REDUCE — chunks → LLM → итоговый DistillResult JSON
  4. VERIFY — проверка галлюцинаций: первая страница исходника + DistillResult → LLM "что пропущено?"

Modes (env DISTILL_MODE):
  - production: реальные LLM-calls
  - canary:     реальные LLM-calls только при совпадении canary_token (для тестов)
  - mock:       заранее заготовленный DistillResult, без LLM-calls (для тестов orchestrator)
"""

from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import os
import re
import tempfile
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import httpx

from schemas import (
    DistillChunkResult,
    DistillRequest,
    DistillResult,
    EmailMetadata,
)


log = logging.getLogger("summary-prep.distill")


# === Конфигурация через env (Docker-friendly + K8s-готовность) ===

DISTILL_MODE = os.getenv("DISTILL_MODE", "production").lower()
DISTILL_CANARY_TOKEN = os.getenv("DISTILL_CANARY_TOKEN", "")
DISTILL_MODEL = os.getenv("DISTILL_MODEL", "anthropic/claude-haiku-4.5")
DISTILL_CHAR_THRESHOLD = int(os.getenv("DISTILL_CHAR_THRESHOLD", "80000"))
DISTILL_CHUNK_CHARS = int(os.getenv("DISTILL_CHUNK_CHARS", "50000"))
DISTILL_MAX_RETRIES = int(os.getenv("DISTILL_MAX_RETRIES", "2"))
DISTILL_PARTIAL_REDUCE_THRESHOLD = float(
    os.getenv("DISTILL_PARTIAL_REDUCE_THRESHOLD", "0.5")
)
DISTILL_CACHE_DIR = Path(os.getenv("DISTILL_CACHE_DIR", "/var/lib/summary-prep/cache"))
DISTILL_CACHE_TTL_DAYS = int(os.getenv("DISTILL_CACHE_TTL_DAYS", "30"))
DISTILL_PROMPT_VERSION = os.getenv("DISTILL_PROMPT_VERSION", "v1")

OPENROUTER_API_KEY = os.getenv("OPENROUTER_API_KEY", "")
OPENROUTER_BASE_URL = os.getenv(
    "OPENROUTER_BASE_URL", "https://openrouter.ai/api/v1"
)
OPENROUTER_TIMEOUT_SEC = float(os.getenv("OPENROUTER_TIMEOUT_SEC", "60"))

# OpenRouter Haiku 4.5 pricing (USD per 1M tokens)
# Источник: openrouter.ai/anthropic/claude-haiku-4.5 — обновлять при изменении цены.
HAIKU_PROMPT_USD_PER_1M = 1.0
HAIKU_COMPLETION_USD_PER_1M = 5.0


# === Промпты ===

MAP_PROMPT = """Ты — экстрактор фактов из фрагмента документа. Не пересказывай. Не интерпретируй.
Не дополняй знаниями. Не выдумывай. Если факта нет в тексте — не упоминай его.

ВАЖНО: Все факты должны быть подкреплены дословной цитатой из текста.

Метаданные письма:
  От: {from_addr}
  Тема: {subject}
  Дата: {date}

Фрагмент документа (chunk {chunk_idx}/{total_chunks}):
---
{chunk_text}
---

Задачи:
1. Определи тип документа из списка: bank_response, tax_declaration, contract,
   act, statement, letter, notice, report, other.
2. Извлеки факты:
   - суммы (с ролью: amount_total/tax/fine/balance/monthly/due/other)
   - даты (с ролью: document_date/due_date/period_start/period_end/signature_date/other)
   - стороны с ИНН если есть (роль: sender/recipient/third_party)
   - требования к получателю
3. Для каждого факта приведи дословную цитату из текста (поле raw_text).
4. Оцени confidence своей экстракции 0-1.

Ответ — JSON по schema DistillChunkResult. Никакого markdown, никакого префикса
"Вот результат:". Только JSON.

Schema:
{{
  "chunk_idx": {chunk_idx},
  "total_chunks": {total_chunks},
  "document_type": "...",
  "key_facts": ["...", ...],
  "amounts": [{{"value": "0.00", "currency": "RUB", "role": "...", "raw_text": "..."}}],
  "dates": [{{"date": "YYYY-MM-DD", "role": "...", "raw_text": "..."}}],
  "parties": [{{"name": "...", "inn": null, "role": "..."}}],
  "requirements": ["...", ...],
  "raw_excerpts": ["...", ...],
  "confidence": 0.0
}}"""


REDUCE_PROMPT = """Ты собираешь набор дистиллятов фрагментов одного документа в единый отчёт.

Метаданные письма:
  От: {from_addr}
  Тема: {subject}
  Дата: {date}

Дистилляты фрагментов:
{chunk_results_json}

Задачи:
1. Определи итоговый document_type (большинство голосов из чанков, при споре —
   тот тип, который согласован с метаданными письма).
2. Сформируй summary_brief: 1-2 предложения, что это за документ и для кого
   (учитывай from/subject).
3. Объедини key_facts из чанков, убери дубликаты. Каждый факт <= 1 предложение.
4. Объедини amounts/dates/parties/requirements, дедуплицируй.
5. Соедини raw_excerpts (выбери 3-5 самых характерных).
6. Итоговый confidence = средневзвешенный по chunks_succeeded.

Не добавляй фактов которых нет в дистиллятах. Не интерпретируй.

Ответ — JSON по schema DistillResult. Только JSON, без префиксов."""


VERIFY_PROMPT = """Тебе дан фрагмент исходного документа (первая страница) и дистиллят, собранный
из него и других фрагментов.

Исходник (первая страница):
{first_chunk_text}

Дистиллят:
{distill_result_json}

Задача: оцени, есть ли в исходнике критически важный факт (сумма, срок,
требование), которого нет в дистилляте.

Ответ — JSON: {{"missing_critical_facts": ["..." или []], "severity": "none"|"low"|"high"}}"""


# === Чанкование ===

def chunk_text(text: str, chunk_size: int = DISTILL_CHUNK_CHARS) -> list[str]:
    """
    Разбить длинный текст на chunks по границам параграфов / предложений.

    Стратегия поиска границы (от лучшего к худшему):
        1. \\n\\n  (пустая строка между параграфами)
        2. \\n    (перенос строки)
        3. '. '   (конец предложения)
        4. ' '    (пробел)
        5. жёсткий cut по chunk_size

    Поиск границы — назад до 1000 символов от жёсткой границы chunk_size.
    """
    if len(text) <= chunk_size:
        return [text]

    chunks = []
    start = 0
    search_window = 1000

    while start < len(text):
        hard_end = start + chunk_size
        if hard_end >= len(text):
            chunks.append(text[start:])
            break

        # Поиск границы назад от hard_end до hard_end - search_window
        search_start = max(start + chunk_size - search_window, start + 1)
        chunk_part = text[search_start:hard_end]

        # Пробуем границы по приоритету
        boundary_offset = -1
        for pattern in ("\n\n", "\n", ". ", " "):
            idx = chunk_part.rfind(pattern)
            if idx > 0:
                boundary_offset = idx + len(pattern)
                break

        if boundary_offset > 0:
            end = search_start + boundary_offset
        else:
            end = hard_end  # жёсткий cut

        chunks.append(text[start:end])
        start = end

    return chunks


# === sha256 кэш ===

def _cache_key(text: str, prompt_version: str = DISTILL_PROMPT_VERSION) -> str:
    """SHA-256 от текста + версия промпта (для инвалидации при изменении промптов)."""
    h = hashlib.sha256()
    h.update(text.encode("utf-8"))
    h.update(b":")
    h.update(prompt_version.encode("utf-8"))
    return h.hexdigest()


def _cache_path(cache_key: str) -> Path:
    return DISTILL_CACHE_DIR / cache_key[:2] / f"{cache_key}.json"


def cache_get(cache_key: str) -> DistillResult | None:
    """Прочитать из FS-кэша. None если miss или expired."""
    path = _cache_path(cache_key)
    if not path.exists():
        return None

    # TTL check по mtime
    age_seconds = time.time() - path.stat().st_mtime
    ttl_seconds = DISTILL_CACHE_TTL_DAYS * 86400
    if age_seconds > ttl_seconds:
        log.info(
            "cache_expired key=%s age_days=%.1f ttl_days=%d",
            cache_key[:16],
            age_seconds / 86400,
            DISTILL_CACHE_TTL_DAYS,
        )
        return None

    try:
        with path.open() as f:
            data = json.load(f)
        return DistillResult(**data)
    except Exception as e:
        log.warning("cache_read_fail key=%s err=%s", cache_key[:16], e)
        return None


def cache_put(cache_key: str, result: DistillResult) -> None:
    """Atomic write через tempfile + os.rename (race-safe)."""
    path = _cache_path(cache_key)
    path.parent.mkdir(parents=True, exist_ok=True)

    fd, tmp_path = tempfile.mkstemp(
        prefix=".tmp_", suffix=".json", dir=str(path.parent)
    )
    try:
        with os.fdopen(fd, "w") as f:
            json.dump(result.model_dump(mode="json"), f, ensure_ascii=False)
        os.rename(tmp_path, path)
    except Exception as e:
        log.warning("cache_write_fail key=%s err=%s", cache_key[:16], e)
        try:
            os.unlink(tmp_path)
        except OSError:
            pass


# === OpenRouter HTTP клиент ===

class OpenRouterError(Exception):
    """Любой не-200 от OpenRouter."""


async def _openrouter_call(
    messages: list[dict[str, str]],
    *,
    model: str = DISTILL_MODEL,
    response_format: dict | None = None,
    max_tokens: int = 2000,
) -> tuple[str, dict[str, Any]]:
    """
    Один LLM call к OpenRouter.

    Returns: (text, usage_dict) где usage = {prompt_tokens, completion_tokens, cost}.
    """
    if not OPENROUTER_API_KEY:
        raise OpenRouterError("OPENROUTER_API_KEY не задан")

    headers = {
        "Authorization": f"Bearer {OPENROUTER_API_KEY}",
        "Content-Type": "application/json",
    }
    payload: dict[str, Any] = {
        "model": model,
        "messages": messages,
        "max_tokens": max_tokens,
    }
    if response_format:
        payload["response_format"] = response_format

    async with httpx.AsyncClient(timeout=OPENROUTER_TIMEOUT_SEC) as client:
        r = await client.post(
            f"{OPENROUTER_BASE_URL}/chat/completions",
            headers=headers,
            json=payload,
        )

    if r.status_code != 200:
        raise OpenRouterError(
            f"OpenRouter HTTP {r.status_code}: {r.text[:300]}"
        )

    data = r.json()
    text = data["choices"][0]["message"]["content"]
    usage = data.get("usage", {}) or {}
    return text, usage


def _calc_cost(usage: dict) -> float:
    """Стоимость в USD исходя из tokens."""
    prompt = usage.get("prompt_tokens", 0)
    completion = usage.get("completion_tokens", 0)
    return (
        prompt * HAIKU_PROMPT_USD_PER_1M / 1_000_000
        + completion * HAIKU_COMPLETION_USD_PER_1M / 1_000_000
    )


# === MAP / REDUCE / VERIFY stages ===

async def map_chunk(
    chunk_idx: int,
    total_chunks: int,
    chunk_text_str: str,
    metadata: EmailMetadata,
    trace_id: str,
) -> tuple[DistillChunkResult, int, float]:
    """MAP одного chunk'а с retry. Returns: (result, tokens, cost)."""
    prompt = MAP_PROMPT.format(
        from_addr=metadata.from_addr,
        subject=metadata.subject,
        date=metadata.date,
        chunk_idx=chunk_idx,
        total_chunks=total_chunks,
        chunk_text=chunk_text_str,
    )

    last_err: Exception | None = None
    for attempt in range(DISTILL_MAX_RETRIES + 1):
        try:
            text, usage = await _openrouter_call(
                messages=[{"role": "user", "content": prompt}],
                response_format={"type": "json_object"},
                max_tokens=2000,
            )
            log.info(
                "map.ok trace=%s chunk=%d/%d attempt=%d tokens=%d",
                trace_id,
                chunk_idx,
                total_chunks,
                attempt,
                usage.get("total_tokens", 0),
            )
            # Парсим JSON ответ
            data = json.loads(text)
            # Защита от LLM не указавшего chunk_idx/total_chunks
            data["chunk_idx"] = chunk_idx
            data["total_chunks"] = total_chunks
            result = DistillChunkResult(**data)
            cost = _calc_cost(usage)
            return result, usage.get("total_tokens", 0), cost
        except Exception as e:
            last_err = e
            log.warning(
                "map.retry trace=%s chunk=%d attempt=%d err=%s",
                trace_id,
                chunk_idx,
                attempt,
                e,
            )
            if attempt < DISTILL_MAX_RETRIES:
                await asyncio.sleep(2**attempt)  # 1s, 2s

    raise RuntimeError(f"map_chunk failed after retries: {last_err}")


async def reduce_chunks(
    chunk_results: list[DistillChunkResult],
    metadata: EmailMetadata,
    trace_id: str,
) -> tuple[DistillResult, int, float]:
    """REDUCE собирает chunks в финальный DistillResult."""
    chunk_results_json = json.dumps(
        [r.model_dump(mode="json") for r in chunk_results],
        ensure_ascii=False,
    )

    prompt = REDUCE_PROMPT.format(
        from_addr=metadata.from_addr,
        subject=metadata.subject,
        date=metadata.date,
        chunk_results_json=chunk_results_json,
    )

    last_err: Exception | None = None
    for attempt in range(DISTILL_MAX_RETRIES + 1):
        try:
            text, usage = await _openrouter_call(
                messages=[{"role": "user", "content": prompt}],
                response_format={"type": "json_object"},
                max_tokens=3000,
            )
            log.info(
                "reduce.ok trace=%s attempt=%d tokens=%d",
                trace_id,
                attempt,
                usage.get("total_tokens", 0),
            )
            data = json.loads(text)
            # Не позволяем LLM перезаписать служебные поля
            data["distillation_applied"] = True
            data["map_chunks_total"] = len(chunk_results)
            data["map_chunks_succeeded"] = len(chunk_results)
            data["trace_id"] = trace_id
            result = DistillResult(**data)
            cost = _calc_cost(usage)
            return result, usage.get("total_tokens", 0), cost
        except Exception as e:
            last_err = e
            log.warning(
                "reduce.retry trace=%s attempt=%d err=%s", trace_id, attempt, e
            )
            if attempt < DISTILL_MAX_RETRIES:
                await asyncio.sleep(2**attempt)

    raise RuntimeError(f"reduce failed after retries: {last_err}")


async def verify_distillation(
    first_chunk_text: str,
    distill_result: DistillResult,
    trace_id: str,
) -> tuple[str, list[str], int, float]:
    """
    VERIFY проверка галлюцинаций на первой странице исходника.
    Returns: (severity, missing_facts, tokens, cost).
    severity: 'none' | 'low' | 'high'.
    """
    distill_json = json.dumps(
        distill_result.model_dump(mode="json"), ensure_ascii=False
    )

    prompt = VERIFY_PROMPT.format(
        first_chunk_text=first_chunk_text[:5000],
        distill_result_json=distill_json,
    )

    try:
        text, usage = await _openrouter_call(
            messages=[{"role": "user", "content": prompt}],
            response_format={"type": "json_object"},
            max_tokens=500,
        )
        data = json.loads(text)
        severity = data.get("severity", "none")
        missing = data.get("missing_critical_facts", [])
        if not isinstance(missing, list):
            missing = []
        log.info(
            "verify.ok trace=%s severity=%s missing=%d",
            trace_id,
            severity,
            len(missing),
        )
        return severity, missing, usage.get("total_tokens", 0), _calc_cost(usage)
    except Exception as e:
        log.warning("verify.fail trace=%s err=%s — pass-through", trace_id, e)
        return "none", [], 0, 0.0


# === Fallback: только метаданные ===

def fallback_metadata_only(
    metadata: EmailMetadata,
    *,
    map_chunks_total: int,
    map_chunks_succeeded: int,
    trace_id: str,
    reason: str,
) -> DistillResult:
    """
    Если <PARTIAL_REDUCE_THRESHOLD chunks succeeded — возвращаем минимальный
    DistillResult с метаданными письма. Дайджест получает уведомление о документе
    без потери самого факта его прихода.
    """
    return DistillResult(
        document_type="other",
        summary_brief=(
            f"Документ не удалось дистиллировать ({reason}). "
            f"См. оригинал во вложении: "
            f"{metadata.attachment_filename or '<filename unknown>'}"
        ),
        key_facts=[
            f"От: {metadata.from_addr or '?'}",
            f"Тема: {metadata.subject or '?'}",
            f"Дата: {metadata.date or '?'}",
            (
                f"Файл: {metadata.attachment_filename}"
                if metadata.attachment_filename
                else "Файл: <неизвестно>"
            ),
        ],
        confidence=0.0,
        distillation_applied=False,
        map_chunks_total=map_chunks_total,
        map_chunks_succeeded=map_chunks_succeeded,
        trace_id=trace_id,
    )


# === As-is wrapped: короткие документы ===

def as_is_wrapped(
    text: str,
    metadata: EmailMetadata,
    trace_id: str,
) -> DistillResult:
    """
    Документ короче DISTILL_CHAR_THRESHOLD — возвращаем DistillResult с самим
    текстом в summary_brief, без LLM-call. Контракт downstream единый.
    """
    summary = text.strip()
    if len(summary) > 500:
        summary = summary[:500] + "..."
    return DistillResult(
        document_type="other",
        summary_brief=summary or "(пустой документ)",
        key_facts=[],
        confidence=1.0,  # we trust raw input, no distillation
        distillation_applied=False,
        map_chunks_total=0,
        map_chunks_succeeded=0,
        trace_id=trace_id,
    )


# === Mock-результат (DISTILL_MODE=mock) ===

def mock_result(
    text: str,
    metadata: EmailMetadata,
    trace_id: str,
) -> DistillResult:
    """Заранее заготовленный DistillResult без LLM-call. Для тестов orchestrator."""
    return DistillResult(
        document_type="other",
        summary_brief=(
            f"[MOCK] Документ длины {len(text)} chars от "
            f"{metadata.from_addr or '?'}, тема: {metadata.subject or '?'}"
        ),
        key_facts=[
            "[MOCK] Это заготовленный mock-результат",
            f"[MOCK] Длина текста: {len(text)} символов",
            f"[MOCK] Файл: {metadata.attachment_filename or 'unknown'}",
        ],
        confidence=0.99,
        distillation_applied=True,
        map_chunks_total=1,
        map_chunks_succeeded=1,
        trace_id=trace_id,
    )


# === Главная функция: дистилляция документа ===

async def distill_document(req: DistillRequest, trace_id: str) -> DistillResult:
    """
    Главный entry-point. Возвращает DistillResult по DEC-0030 контракту.

    Логика режимов:
      - DISTILL_MODE=mock     → mock_result
      - DISTILL_MODE=canary   → требует req.canary_token == DISTILL_CANARY_TOKEN
      - DISTILL_MODE=production → штатный путь

    Логика длины:
      - len(text) < DISTILL_CHAR_THRESHOLD → as_is_wrapped (без LLM)
      - иначе → map-reduce
    """
    text = req.text
    metadata = req.metadata

    # === Режим mock ===
    if DISTILL_MODE == "mock":
        log.info("distill.mock trace=%s text_len=%d", trace_id, len(text))
        return mock_result(text, metadata, trace_id)

    # === Режим canary - проверка токена ===
    if DISTILL_MODE == "canary":
        if req.canary_token != DISTILL_CANARY_TOKEN or not DISTILL_CANARY_TOKEN:
            raise PermissionError(
                "DISTILL_MODE=canary, требуется валидный canary_token"
            )

    # === Короткий документ — as-is ===
    if len(text) < DISTILL_CHAR_THRESHOLD:
        log.info(
            "distill.as_is trace=%s text_len=%d threshold=%d",
            trace_id,
            len(text),
            DISTILL_CHAR_THRESHOLD,
        )
        return as_is_wrapped(text, metadata, trace_id)

    # === Cache check ===
    cache_key = _cache_key(text)
    cached = cache_get(cache_key)
    if cached is not None:
        cached.cache_hit = True
        cached.trace_id = trace_id
        log.info(
            "distill.cache_hit trace=%s key=%s text_len=%d",
            trace_id,
            cache_key[:16],
            len(text),
        )
        return cached

    # === Map-reduce ===
    chunks = chunk_text(text)
    log.info(
        "distill.start trace=%s text_len=%d chunks=%d threshold=%d",
        trace_id,
        len(text),
        len(chunks),
        DISTILL_CHAR_THRESHOLD,
    )

    # MAP параллельно
    map_tasks = [
        map_chunk(i + 1, len(chunks), c, metadata, trace_id)
        for i, c in enumerate(chunks)
    ]
    map_results_raw = await asyncio.gather(*map_tasks, return_exceptions=True)

    succeeded_with_tokens: list[tuple[DistillChunkResult, int, float]] = [
        r for r in map_results_raw if not isinstance(r, Exception)
    ]
    failures = [r for r in map_results_raw if isinstance(r, Exception)]
    succeeded = [r for r, _, _ in succeeded_with_tokens]
    total_tokens = sum(t for _, t, _ in succeeded_with_tokens)
    total_cost = sum(c for _, _, c in succeeded_with_tokens)

    log.info(
        "distill.map_done trace=%s succeeded=%d/%d failures=%d tokens=%d cost=%.4f",
        trace_id,
        len(succeeded),
        len(chunks),
        len(failures),
        total_tokens,
        total_cost,
    )

    # Partial-reduce gate
    if len(succeeded) / len(chunks) < DISTILL_PARTIAL_REDUCE_THRESHOLD:
        return fallback_metadata_only(
            metadata,
            map_chunks_total=len(chunks),
            map_chunks_succeeded=len(succeeded),
            trace_id=trace_id,
            reason=f"only {len(succeeded)}/{len(chunks)} chunks succeeded",
        )

    # REDUCE
    try:
        result, reduce_tokens, reduce_cost = await reduce_chunks(
            succeeded, metadata, trace_id
        )
        total_tokens += reduce_tokens
        total_cost += reduce_cost
    except Exception as e:
        log.error("distill.reduce_fail trace=%s err=%s — fallback", trace_id, e)
        return fallback_metadata_only(
            metadata,
            map_chunks_total=len(chunks),
            map_chunks_succeeded=len(succeeded),
            trace_id=trace_id,
            reason=f"reduce failed: {e}",
        )

    # VERIFY (best-effort, не критично)
    severity, missing_facts, verify_tokens, verify_cost = await verify_distillation(
        chunks[0], result, trace_id
    )
    total_tokens += verify_tokens
    total_cost += verify_cost

    result.verify_severity = severity
    if severity == "high":
        result.confidence = max(0.0, result.confidence - 0.3)
        result.verify_warnings = list(missing_facts)
        result.key_facts.insert(
            0, "ВНИМАНИЕ: возможен пропуск критических фактов, см. оригинал"
        )

    # Заполняем финальные метаданные
    result.map_chunks_total = len(chunks)
    result.map_chunks_succeeded = len(succeeded)
    result.total_tokens = total_tokens
    result.total_cost_usd = total_cost
    result.contract_strictness = req.contract_strictness
    result.trace_id = trace_id
    result.cache_hit = False

    # Кладём в кэш
    cache_put(cache_key, result)

    log.info(
        "distill.done trace=%s severity=%s confidence=%.2f tokens=%d cost=%.4f",
        trace_id,
        severity,
        result.confidence,
        total_tokens,
        total_cost,
    )

    return result
