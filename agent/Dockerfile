FROM python:3.11-slim

WORKDIR /app

RUN pip install --no-cache-dir \
    anthropic \
    psycopg2-binary \
    requests

# Build context = repo root
COPY agent.py .
COPY agent_bot.py .

ENV PYTHONUNBUFFERED=1

CMD ["python3", "agent_bot.py"]