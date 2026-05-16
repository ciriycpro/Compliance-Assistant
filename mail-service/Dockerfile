FROM python:3.11-slim

WORKDIR /app

# Зависимости — отдельным слоем для кэша
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Код
COPY server.py .

# Healthcheck — Docker сам проверяет /health
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8765/health', timeout=3).read()" || exit 1

# Запуск на 0.0.0.0, чтобы из других контейнеров и с хоста было доступно
EXPOSE 8765
CMD ["uvicorn", "server:app", "--host", "0.0.0.0", "--port", "8765"]
