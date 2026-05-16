FROM python:3.11-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY server.py .

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8768/health', timeout=3).read()" || exit 1

EXPOSE 8768
CMD ["uvicorn", "server:app", "--host", "0.0.0.0", "--port", "8768"]
