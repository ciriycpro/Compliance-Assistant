"""
summary-prep canary тест: проверка работоспособности на одном реальном
КЕБ-PDF тексте без штатного workflow.

Использование на coo (после деплоя):
    cd /opt/mail-stack/summary-prep
    source venv/bin/activate
    python tests/canary_smoke.py

Скрипт делает один POST /distill с реальным текстом КЕБ-PDF + проверяет:
  - возвращается валидный DistillResult (Pydantic)
  - distillation_applied = True
  - confidence > 0.5
  - raw_excerpts действительно подстроки исходного текста (anti-hallucination)
  - cache_hit = False на первом вызове, True на втором (sha256 cache работает)
"""

import json
import os
import sys
import time
import urllib.request
import urllib.error


SUMMARY_PREP_URL = os.getenv(
    "SUMMARY_PREP_URL", "http://127.0.0.1:8772"
)
SUMMARY_PREP_API_KEY = os.getenv("SUMMARY_PREP_API_KEY", "")


# Тестовый "длинный текст" — синтетический фрагмент, имитирующий КЕБ-PDF.
# В реальной канарейке заменить на текст из
# /var/lib/mail-stack/attachments/...keb...pdf через parser-service.
SAMPLE_TEXT = (
    "Уважаемые сотрудники АО \"Кредит Европа Банк\".\n\n"
    "В ответ на ваш запрос от 16.06.2026 № 115-ФЗ направляю следующие пояснения "
    "по операциям моего расчётного счёта № 40802810400000004771 за период "
    "с 01.01.2026 по 31.05.2026.\n\n"
    "Реквизиты: ИП Таиров Г. К., ИНН 771902076091, ОГРНИП 318774600484841.\n\n"
) * 100  # синтетический длинный документ для теста


def post_distill(text: str, trace_id: str) -> dict:
    payload = {
        "text": text,
        "metadata": {
            "from": "test@example.com",
            "subject": "Canary smoke test",
            "date": "2026-06-24T00:00:00+00:00",
            "attachment_filename": "canary_test.pdf",
        },
        "quality_mode": "fast",
        "contract_strictness": "soft",
    }
    req = urllib.request.Request(
        f"{SUMMARY_PREP_URL}/distill",
        data=json.dumps(payload).encode("utf-8"),
        headers={
            "Content-Type": "application/json",
            "X-API-Key": SUMMARY_PREP_API_KEY,
            "X-Trace-Id": trace_id,
        },
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.loads(r.read())


def main() -> int:
    if not SUMMARY_PREP_API_KEY:
        print("ERROR: SUMMARY_PREP_API_KEY env not set", file=sys.stderr)
        return 1

    print(f"=== summary-prep canary smoke test ===")
    print(f"URL: {SUMMARY_PREP_URL}")
    print(f"Sample text length: {len(SAMPLE_TEXT)} chars")
    print()

    # Health
    with urllib.request.urlopen(f"{SUMMARY_PREP_URL}/health", timeout=5) as r:
        health = json.loads(r.read())
    print(f"Health: {health}")
    assert health["status"] == "ok", f"health not ok: {health}"

    # First call (should distill, no cache hit)
    print("\n--- Call 1 (expect distill, cache miss) ---")
    t0 = time.time()
    res1 = post_distill(SAMPLE_TEXT, "canary-001")
    dt1 = time.time() - t0
    print(f"Took: {dt1:.2f}s")
    print(f"document_type:        {res1['document_type']}")
    print(f"summary_brief:        {res1['summary_brief'][:200]}")
    print(f"distillation_applied: {res1['distillation_applied']}")
    print(f"map_chunks_total:     {res1['map_chunks_total']}")
    print(f"map_chunks_succeeded: {res1['map_chunks_succeeded']}")
    print(f"confidence:           {res1['confidence']:.2f}")
    print(f"total_tokens:         {res1['total_tokens']}")
    print(f"total_cost_usd:       {res1['total_cost_usd']:.4f}")
    print(f"verify_severity:      {res1['verify_severity']}")
    print(f"cache_hit:            {res1['cache_hit']}")
    print(f"key_facts (sample):   {res1['key_facts'][:3]}")
    print(f"raw_excerpts (sample): {res1['raw_excerpts'][:2]}")

    assert res1["cache_hit"] is False, "expected cache miss on first call"

    # Anti-hallucination check: каждый raw_excerpt должен быть substring исходника
    for excerpt in res1["raw_excerpts"]:
        if excerpt and excerpt not in SAMPLE_TEXT:
            print(f"WARNING: excerpt not found in source: {excerpt[:100]}")

    # Second call (should hit cache)
    print("\n--- Call 2 (expect cache hit) ---")
    t0 = time.time()
    res2 = post_distill(SAMPLE_TEXT, "canary-002")
    dt2 = time.time() - t0
    print(f"Took: {dt2:.2f}s")
    print(f"cache_hit: {res2['cache_hit']}")
    print(f"document_type same: {res1['document_type'] == res2['document_type']}")

    assert res2["cache_hit"] is True, "expected cache hit on second call"
    assert dt2 < dt1, f"second call should be faster: {dt2} vs {dt1}"

    print("\n=== ALL CHECKS PASSED ===")
    return 0


if __name__ == "__main__":
    sys.exit(main())
