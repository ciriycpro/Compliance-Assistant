# summary-prep multi-stage Dockerfile (DEC-0030 + DEC-007)
#
# Docker-friendly:
#   - Listen 0.0.0.0 в контейнере (через ENV)
#   - Логи в stdout (JSON via JsonFormatter)
#   - Healthcheck endpoint /health
#   - Env-driven конфигурация (никакого хардкода путей)
#   - Pinned зависимости (requirements.txt)
#   - Graceful shutdown через uvicorn
#
# K8s-готовность:
#   - liveness via /health, readiness via /readiness
#   - PVC mount /var/lib/summary-prep/cache via VOLUME
#   - Non-root user 1000:1000
#   - JSON logs в stdout

# === Stage 1: builder ===
FROM python:3.11-slim AS builder

WORKDIR /build

# Системные зависимости только для сборки (если бы нужны были — здесь)
# Для чистого Python проекта не требуются.

# Зависимости отдельным слоем для Docker layer cache
COPY requirements.txt .
RUN pip install --no-cache-dir --user --no-warn-script-location -r requirements.txt


# === Stage 2: runtime ===
FROM python:3.11-slim AS runtime

WORKDIR /app

# Создаём non-root пользователя (uid 1000)
RUN groupadd -g 1000 summaryprep && \
    useradd -u 1000 -g 1000 -m -d /home/summaryprep -s /sbin/nologin summaryprep && \
    mkdir -p /var/lib/summary-prep/cache && \
    chown -R 1000:1000 /var/lib/summary-prep

# Копируем python пакеты из builder в site-packages root user'а
COPY --from=builder /root/.local /home/summaryprep/.local
ENV PATH=/home/summaryprep/.local/bin:$PATH
RUN chown -R 1000:1000 /home/summaryprep/.local

# Копируем код
COPY --chown=1000:1000 server.py distill.py schemas.py ./

# Volume для кэша — K8s mount this as PVC
VOLUME ["/var/lib/summary-prep/cache"]

# В контейнере слушаем 0.0.0.0 (DEC-007 правило 2)
ENV SUMMARY_PREP_HTTP_HOST=0.0.0.0
ENV SUMMARY_PREP_HTTP_PORT=8772
ENV DISTILL_CACHE_DIR=/var/lib/summary-prep/cache

# Healthcheck — Docker сам опрашивает /health
HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8772/health', timeout=3).read()" || exit 1

USER 1000:1000

EXPOSE 8772

CMD ["uvicorn", "server:app", "--host", "0.0.0.0", "--port", "8772"]
