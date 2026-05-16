FROM python:3.10-slim

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
    libgl1 \
    && rm -rf /var/lib/apt/lists/*

# Ставим pdf-inspector из локального cp310 wheel (БЕЗ Rust)
COPY pdf_inspector-0.1.1-cp310-cp310-linux_x86_64.whl /tmp/
RUN pip install --no-cache-dir /tmp/pdf_inspector-*.whl && rm /tmp/pdf_inspector-*.whl

# Все остальные зависимости (БЕЗ pdf-inspector)
COPY requirements-runtime.txt .
RUN pip install --no-cache-dir -r requirements-runtime.txt

COPY server.py .

VOLUME ["/var/lib/mail-stack/attachments"]

HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8767/health', timeout=3).read()" || exit 1

EXPOSE 8767
CMD ["uvicorn", "server:app", "--host", "0.0.0.0", "--port", "8767"]
