"""
summary-prep schemas (DEC-0030).

Pydantic-модели контракта /distill endpoint.

Soft contract (MVP) — структура валидируется, пустые списки разрешены.
Hard contract (future, DEC-0028 Phase 2) — strict-mode + обязательные поля
для типа документа.
"""

from __future__ import annotations

from datetime import date
from decimal import Decimal
from typing import Literal

from pydantic import BaseModel, Field


# === Базовые типы ===

QualityMode = Literal["fast", "deep"]
ContractStrictness = Literal["soft", "hard"]

DocumentType = Literal[
    "bank_response",     # ответ банку (115-ФЗ, запросы)
    "tax_declaration",   # декларация НДС/УСН и т.п.
    "contract",          # договор
    "act",               # акт
    "statement",         # банковская выписка
    "letter",            # деловое письмо
    "notice",            # уведомление
    "report",            # отчёт
    "other",             # прочее, не классифицировано
]


# === Вложенные структуры ===

class Amount(BaseModel):
    """Сумма с ролью и (опциональной) дословной цитатой источника."""

    value: Decimal = Field(..., description="Численное значение")
    currency: str = Field(default="RUB", description="Код валюты ISO-4217")
    role: str = Field(
        ...,
        description=(
            "Семантическая роль: amount_total | tax | fine | balance | "
            "monthly | due | other"
        ),
    )
    raw_text: str | None = Field(
        default=None,
        description=(
            "Дословная цитата из источника. Optional в fast-mode (саммари); "
            "обязательно проверяется на наличие при contract_strictness='hard'."
        ),
    )


class DateEntry(BaseModel):
    """Дата с ролью."""

    date: date
    role: str = Field(
        ...,
        description=(
            "Семантическая роль: document_date | due_date | "
            "period_start | period_end | signature_date | other"
        ),
    )
    raw_text: str | None = Field(
        default=None,
        description=(
            "Дословная цитата из источника. Optional в fast-mode (саммари); "
            "обязательно проверяется на наличие при contract_strictness='hard'."
        ),
    )


class Party(BaseModel):
    """Сторона документа (отправитель / получатель / третья сторона)."""

    name: str = Field(..., description="Наименование организации или ФИО")
    inn: str | None = Field(default=None, description="ИНН если присутствует")
    role: str = Field(
        ...,
        description="sender | recipient | third_party",
    )


# === Метаданные письма (входной контекст) ===

class EmailMetadata(BaseModel):
    """Контекст письма-носителя документа."""

    from_addr: str = Field(default="", alias="from")
    subject: str = Field(default="")
    date: str = Field(default="", description="ISO 8601")
    message_id: str | None = Field(default=None)
    attachment_filename: str | None = Field(default=None)

    model_config = {"populate_by_name": True}


# === Запрос ===

class DistillRequest(BaseModel):
    """Запрос на дистилляцию документа."""

    text: str = Field(..., description="Plain text документа после parser-service")
    metadata: EmailMetadata = Field(default_factory=EmailMetadata)
    quality_mode: QualityMode = Field(
        default="fast",
        description="fast (Haiku, MVP) | deep (Sonnet, future DEC-029)",
    )
    contract_strictness: ContractStrictness = Field(
        default="soft",
        description="soft (саммари MVP) | hard (compliance-logic future)",
    )
    canary_token: str | None = Field(
        default=None,
        description=(
            "Если DISTILL_MODE=canary, обязателен и сверяется с env "
            "DISTILL_CANARY_TOKEN. Иначе HTTP 403."
        ),
    )


# === Результат MAP стадии (промежуточный) ===

class DistillChunkResult(BaseModel):
    """Результат дистилляции одного chunk'а (MAP stage)."""

    chunk_idx: int
    total_chunks: int
    document_type: DocumentType
    key_facts: list[str] = Field(default_factory=list)
    amounts: list[Amount] = Field(default_factory=list)
    dates: list[DateEntry] = Field(default_factory=list)
    parties: list[Party] = Field(default_factory=list)
    requirements: list[str] = Field(default_factory=list)
    raw_excerpts: list[str] = Field(default_factory=list)
    confidence: float = Field(default=0.0, ge=0.0, le=1.0)


# === Итоговый результат ===

class DistillResult(BaseModel):
    """Результат дистилляции документа (REDUCE stage)."""

    document_type: DocumentType
    summary_brief: str = Field(
        ...,
        description="1-2 предложения, что это за документ и для кого",
    )
    key_facts: list[str] = Field(default_factory=list)
    amounts: list[Amount] = Field(default_factory=list)
    dates: list[DateEntry] = Field(default_factory=list)
    parties: list[Party] = Field(default_factory=list)
    requirements: list[str] = Field(default_factory=list)
    raw_excerpts: list[str] = Field(default_factory=list)

    confidence: float = Field(default=0.0, ge=0.0, le=1.0)
    distillation_applied: bool = Field(
        default=False,
        description="True если был map-reduce, False если документ as-is",
    )
    map_chunks_total: int = Field(default=0)
    map_chunks_succeeded: int = Field(default=0)
    contract_strictness: ContractStrictness = Field(default="soft")

    # Cost-tracking
    total_tokens: int = Field(default=0)
    total_cost_usd: float = Field(default=0.0)

    # Verify stage (anti-hallucination)
    verify_severity: Literal["none", "low", "high"] = Field(default="none")
    verify_warnings: list[str] = Field(default_factory=list)

    # Trace
    trace_id: str | None = Field(default=None)
    cache_hit: bool = Field(default=False)


# === Health / Readiness ===

class HealthResponse(BaseModel):
    status: Literal["ok", "degraded", "down"]
    service: str = "summary-prep-v1"
    version: str = "1.0.0"


class ReadinessResponse(BaseModel):
    """Готов ли сервис принимать трафик."""

    ready: bool
    checks: dict[str, bool] = Field(
        default_factory=dict,
        description="api_key_set, openrouter_reachable, cache_dir_writable",
    )


# === Metrics ===

class MetricsResponse(BaseModel):
    distill_calls_total: int = 0
    distill_calls_succeeded: int = 0
    distill_calls_failed: int = 0
    distill_cache_hits_total: int = 0
    distill_map_calls_total: int = 0
    distill_reduce_calls_total: int = 0
    distill_verify_calls_total: int = 0
    distill_tokens_total: int = 0
    distill_cost_usd_total: float = 0.0
    uptime_seconds: float = 0.0
