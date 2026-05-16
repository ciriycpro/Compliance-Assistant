FROM python:3.11-slim

WORKDIR /app

# Зависимости — отдельным слоем для кэша
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Код
COPY server.py .

# Volume для attachments — должен монтироваться извне
VOLUME ["/var/lib/mail-stack/attachments"]

# Healthcheck
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8766/health', timeout=3).read()" || exit 1

EXPOSE 8766
CMD ["uvicorn", "server:app", "--host", "0.0.0.0", "--port", "8766"]
