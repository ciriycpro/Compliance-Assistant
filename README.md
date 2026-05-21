# Compliance Assistant — mail-stack production code

Production code for **Compliance Assistant** (DEC-014 v1.2.2) — multi-service mail processing stack running on coo (GCP e2-small).

## Точка входа для AI-ассистента

Архитектурные решения (ADR, C4 diagrams, Service Blueprint) живут отдельно:  
👉 **https://github.com/ciriycpro/architect** — workspace `tairov/`

Здесь только **production-код** 7 микросервисов в их текущем состоянии. Когда нужен контекст «почему так» — смотри ADR в `architect` репо.

## Сервисы

| Сервис | Стек | Порт | Версия | ADR |
|---|---|---|---|---|
| `orchestrator/` | Go 1.22 + net/http + cron + slog | 8769 | v1.2.2 | DEC-014 |
| `state-service/` | Go 1.22 + go-redis + chi | 8770 | v1.0 | DEC-021 |
| `mail-service/` | Python 3.11 + FastAPI + IMAP | 8765 | v1.1 | DEC-007 |
| `attachment-service/` | Python 3.11 + FastAPI | 8766 | v1.0 | DEC-011 |
| `parser-service/` | Python 3.10 + FastAPI + LLM-vision | 8767 | v1.0 | DEC-008 |
| `summary-service/` | Python 3.11 + FastAPI + Claude Haiku | 8768 | v1.0 | DEC-009 |
| `agent-caller/` | Node.js + whatsapp-web.js + node-telegram-bot-api | 3000 | v1.2.2 | DEC-005 |

## Архитектурный поток (production)
Cron (10:00 МСК) ──┐                       ┌── Telegram дайджест
├─► orchestrator ──────┤
Кнопка ──► agent-caller ─┘    │            └── WhatsApp pre-alert (cron only)
│
┌─────────────┼─────────────┐
▼             ▼             ▼
mail-service   state-service   attachment-service
│                            │
│                            ▼
│                      parser-service ─► OpenRouter (Qwen-VL)
│                            │
└─► summary-service ◄────────┘
│
▼
OpenRouter (Claude Haiku 4.5)

## Deployment

Сервисы развёрнуты через systemd на coo. Env-файлы в `/etc/mail-stack/*.env` (chmod 600 root:root) **не в git** — шаблоны лежат в `deploy/env-templates/`.

Unit-файлы systemd в `deploy/systemd/`.

## Версионирование

Это **зеркало production-состояния**, не полная git-история разработки. Коммиты в этот репо делаются при major изменениях в production. История архитектурных решений — в `architect` repo (ADR + DSL).

## Что НЕ в репо

- `*.env` файлы (секреты)
- `node_modules/`, `venv/`, `__pycache__/`, бинарники
- WhatsApp Web сессия (`agent-caller/.wwebjs_auth/`)
- Логи, бэкапы

## Каноны

См. **DEC-007** (docker-friendly), **DEC-014** (полиглот-стек), **DEC-017** (secure-by-design Уровень 0), **DEC-022** (mail-stack as platform) в репо `architect`.

## Контакт

- Артём Якшин, founder СИРИУС ПРО
- inbox@ciriyc.ru
- Telegram: @Economexer
