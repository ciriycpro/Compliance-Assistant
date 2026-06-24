"""
summary-prep server v1.0 (DEC-0030).

FastAPI HTTP service на coo:8772 для дистилляции длинных документов.

Endpoints:
  POST /distill      — главный endpoint, принимает DistillRequest → DistillResult
  GET  /health       — liveness (процесс жив)
  GET  /readiness    — readiness (готов принимать трафик)
  GET  /metrics      — Prometheus-friendly счётчики

Docker-friendly (DEC-007): bind на 0.0.0.0 в контейнере, env-only config.
K8s-готовность: liveness + readiness разделены, logs в stdout JSON.
Secure by design (DEC-017): X-API-Key constant-time compare, path validation.
"""

from __future__ import annotations

import hmac
import json
import logging
import os
import time
import uuid
from contextlib import asynccontextmanager
from pathlib import Path

import httpx
from fastapi import FastAPI, Header, HTTPException, Request
from fastapi.responses import JSONResponse

from distill import (
    DISTILL_CACHE_DIR,
    DISTILL_MODE,
    OPENROUTER_API_KEY,
    OPENROUTER_BASE_URL,
    distill_document,
)
from schemas import (
    DistillRequest,
    DistillResult,
    HealthResponse,
    MetricsResponse,
    ReadinessResponse,
)


# === Конфиг через env ===

SUMMARY_PREP_HTTP_HOST = os.getenv("SUMMARY_PREP_HTTP_HOST", "127.0.0.1")
SUMMARY_PREP_HTTP_PORT = int(os.getenv("SUMMARY_PREP_HTTP_PORT", "8772"))
SUMMARY_PREP_API_KEY = os.getenv("SUMMARY_PREP_API_KEY", "")
SUMMARY_PREP_LOG_API_KEY_PREFIX = (
    os.getenv("SUMMARY_PREP_LOG_API_KEY_PREFIX", "false").lower() == "true"
)


# === Logging (structured JSON в stdout — K8s-friendly) ===

class JsonFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        payload = {
            "time": self.formatTime(record, datefmt="%Y-%m-%dT%H:%M:%S.%fZ"),
            "level": record.levelname,
            "service": "summary-prep",
            "msg": record.getMessage(),
            "logger": record.name,
        }
        if record.exc_info:
            payload["exc_info"] = self.formatException(record.exc_info)
        return json.dumps(payload, ensure_ascii=False)


def setup_logging() -> None:
    root = logging.getLogger()
    root.setLevel(logging.INFO)
    handler = logging.StreamHandler()
    handler.setFormatter(JsonFormatter())
    # Чистим default handlers
    for h in list(root.handlers):
        root.removeHandler(h)
    root.addHandler(handler)


setup_logging()
log = logging.getLogger("summary-prep.server")


# === Метрики (in-memory) ===

class Metrics:
    def __init__(self) -> None:
        self.start_time = time.time()
        self.distill_calls_total = 0
        self.distill_calls_succeeded = 0
        self.distill_calls_failed = 0
        self.distill_cache_hits_total = 0
        self.distill_tokens_total = 0
        self.distill_cost_usd_total = 0.0

    def snapshot(self) -> MetricsResponse:
        return MetricsResponse(
            distill_calls_total=self.distill_calls_total,
            distill_calls_succeeded=self.distill_calls_succeeded,
            distill_calls_failed=self.distill_calls_failed,
            distill_cache_hits_total=self.distill_cache_hits_total,
            distill_tokens_total=self.distill_tokens_total,
            distill_cost_usd_total=self.distill_cost_usd_total,
            uptime_seconds=time.time() - self.start_time,
        )


metrics = Metrics()


# === Auth helper ===

def _check_api_key(provided: str | None) -> None:
    """Constant-time API key check. Бросает 401 если не совпало."""
    if not SUMMARY_PREP_API_KEY:
        # Если ключ не задан в env — пускаем всех (только для локальной отладки)
        log.warning("api_key_check_skipped (SUMMARY_PREP_API_KEY not set)")
        return

    if not provided:
        raise HTTPException(status_code=401, detail="X-API-Key header missing")

    if not hmac.compare_digest(provided, SUMMARY_PREP_API_KEY):
        if SUMMARY_PREP_LOG_API_KEY_PREFIX:
            log.warning("api_key_invalid prefix=%s", provided[:6])
        raise HTTPException(status_code=401, detail="Invalid X-API-Key")


# === Readiness check ===

async def _check_readiness() -> ReadinessResponse:
    """
    Проверяем три условия:
      - SUMMARY_PREP_API_KEY и OPENROUTER_API_KEY заданы
      - cache dir writable
      - OpenRouter API reachable (опционально, не блокирующий)
    """
    checks = {
        "api_key_set": bool(SUMMARY_PREP_API_KEY) or DISTILL_MODE == "mock",
        "openrouter_key_set": bool(OPENROUTER_API_KEY) or DISTILL_MODE == "mock",
        "cache_dir_writable": False,
        "openrouter_reachable": False,
    }

    # Cache dir
    try:
        DISTILL_CACHE_DIR.mkdir(parents=True, exist_ok=True)
        test_file = DISTILL_CACHE_DIR / ".readiness_check"
        test_file.write_text("ok")
        test_file.unlink()
        checks["cache_dir_writable"] = True
    except Exception as e:
        log.warning("readiness.cache_dir_fail err=%s", e)

    # OpenRouter (best-effort, не блокируем readiness если LLM лежит)
    if OPENROUTER_API_KEY and DISTILL_MODE != "mock":
        try:
            async with httpx.AsyncClient(timeout=3) as client:
                r = await client.get(
                    f"{OPENROUTER_BASE_URL}/models",
                    headers={"Authorization": f"Bearer {OPENROUTER_API_KEY}"},
                )
                checks["openrouter_reachable"] = r.status_code == 200
        except Exception as e:
            log.info("readiness.openrouter_unreachable err=%s", e)

    # Ready если все базовые ок (openrouter best-effort).
    ready = (
        checks["api_key_set"]
        and checks["openrouter_key_set"]
        and checks["cache_dir_writable"]
    )
    return ReadinessResponse(ready=ready, checks=checks)


# === Lifespan: startup/shutdown ===

@asynccontextmanager
async def lifespan(app: FastAPI):
    log.info(
        "startup mode=%s threshold=%s chunk_chars=%s cache_dir=%s",
        DISTILL_MODE,
        os.getenv("DISTILL_CHAR_THRESHOLD", "80000"),
        os.getenv("DISTILL_CHUNK_CHARS", "50000"),
        DISTILL_CACHE_DIR,
    )
    # Ensure cache dir exists
    try:
        DISTILL_CACHE_DIR.mkdir(parents=True, exist_ok=True)
    except Exception as e:
        log.warning("startup.cache_dir_fail err=%s", e)

    yield

    log.info("shutdown")


# === FastAPI app ===

app = FastAPI(title="summary-prep", version="1.0.0", lifespan=lifespan)


@app.get("/health", response_model=HealthResponse)
def health() -> HealthResponse:
    """Liveness — процесс жив."""
    return HealthResponse(status="ok")


@app.get("/readiness", response_model=ReadinessResponse)
async def readiness() -> ReadinessResponse:
    """Readiness — готов принимать трафик."""
    return await _check_readiness()


@app.get("/metrics", response_model=MetricsResponse)
def get_metrics(
    x_api_key: str | None = Header(default=None, alias="X-API-Key"),
) -> MetricsResponse:
    _check_api_key(x_api_key)
    return metrics.snapshot()


@app.post("/distill", response_model=DistillResult)
async def distill(
    req: DistillRequest,
    request: Request,
    x_api_key: str | None = Header(default=None, alias="X-API-Key"),
    x_trace_id: str | None = Header(default=None, alias="X-Trace-Id"),
) -> DistillResult:
    """
    Главный endpoint. Принимает DistillRequest, возвращает DistillResult.

    Headers:
      X-API-Key: required (constant-time check)
      X-Trace-Id: optional, иначе генерируется UUID v7-like
    """
    _check_api_key(x_api_key)
    trace_id = x_trace_id or str(uuid.uuid4())

    metrics.distill_calls_total += 1

    try:
        result = await distill_document(req, trace_id=trace_id)
    except PermissionError as e:
        # Canary token mismatch
        metrics.distill_calls_failed += 1
        log.warning("distill.forbidden trace=%s err=%s", trace_id, e)
        raise HTTPException(status_code=403, detail=str(e))
    except ValueError as e:
        # Hard contract violation (raw_text missing в deep+hard режиме)
        if str(e).startswith("hard_contract_violation"):
            metrics.distill_calls_failed += 1
            log.warning("distill.hard_violation trace=%s err=%s", trace_id, e)
            raise HTTPException(status_code=422, detail=str(e))
        metrics.distill_calls_failed += 1
        log.error("distill.value_error trace=%s err=%s", trace_id, e, exc_info=True)
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        metrics.distill_calls_failed += 1
        log.error("distill.error trace=%s err=%s", trace_id, e, exc_info=True)
        raise HTTPException(status_code=500, detail=f"distill failed: {e}")

    metrics.distill_calls_succeeded += 1
    metrics.distill_tokens_total += result.total_tokens
    metrics.distill_cost_usd_total += result.total_cost_usd
    if result.cache_hit:
        metrics.distill_cache_hits_total += 1

    return result


# === Глобальный exception handler — структурный JSON ===

@app.exception_handler(Exception)
async def global_exception_handler(request: Request, exc: Exception):
    log.error("unhandled_exception path=%s err=%s", request.url.path, exc, exc_info=True)
    return JSONResponse(
        status_code=500,
        content={"detail": f"internal error: {exc}"},
    )


# === Точка входа (для прямого запуска: python server.py) ===

if __name__ == "__main__":
    import uvicorn

    uvicorn.run(
        "server:app",
        host=SUMMARY_PREP_HTTP_HOST,
        port=SUMMARY_PREP_HTTP_PORT,
        log_config=None,  # используем наш JsonFormatter
    )
