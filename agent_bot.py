"""Telegram long-polling wrapper around agent.py.

Runs as a service. Listens for text messages to @UPIAgent_bot,
routes them through agent.py's ask() loop, replies in-thread.
Hard-locked to ALLOWED_CHAT_ID — silently ignores anyone else.

Env vars:
  AGENT_TELEGRAM_BOT_TOKEN   (required)  bot token from @BotFather
  ALLOWED_CHAT_ID            (required)  your Telegram numeric chat id
  ANTHROPIC_API_KEY          (required)  inherited by agent.py
  PGHOST/PGPORT/PGDATABASE/PGUSER/PGPASSWORD  (required)  inherited by agent.py

Run:  python3 agent_bot.py
"""
import os
import sys
import time
import traceback
from collections import defaultdict

import requests

# Import agent lazily so its module-level DB connect runs after env is set.
import agent   # noqa: E402  — sets up DB conn & SYSTEM prompt on import

BOT_TOKEN = os.environ["AGENT_TELEGRAM_BOT_TOKEN"]
ALLOWED_CHAT_ID = int(os.environ["ALLOWED_CHAT_ID"])
API = f"https://api.telegram.org/bot{BOT_TOKEN}"

# Per-chat conversation history (only one chat allowed, but keeps future doors open)
histories: dict[int, list] = defaultdict(list)

# Build system prompt once at startup
agent.SYSTEM = agent.build_system_prompt()


def send(chat_id: int, text: str) -> None:
    """Send a text reply. Telegram caps at 4096 chars — chunk if longer."""
    for chunk in _chunks(text, 4000):
        try:
            requests.post(
                f"{API}/sendMessage",
                json={"chat_id": chat_id, "text": chunk, "parse_mode": "Markdown"},
                timeout=15,
            )
        except Exception as e:
            print(f"[send] failed: {e}", flush=True)


def _chunks(s: str, n: int):
    for i in range(0, len(s), n):
        yield s[i:i + n]


def handle_message(msg: dict) -> None:
    chat_id = msg.get("chat", {}).get("id")
    text = (msg.get("text") or "").strip()

    if chat_id != ALLOWED_CHAT_ID:
        # Silently ignore anyone else. Do NOT reply — no signal to strangers.
        print(f"[reject] chat_id={chat_id}", flush=True)
        return

    if not text:
        return  # ignore stickers, photos, etc.

    if text.lower() in ("/start", "/help"):
        send(chat_id,
             "Hi Ayush. Ask me anything about your finances.\n\n"
             "Examples:\n"
             "• how much on food in march?\n"
             "• total sent to abhishek this year?\n"
             "• biggest expense last week?\n"
             "• what's my current balance?")
        return

    if text.lower() == "/reset":
        histories[chat_id] = []
        send(chat_id, "History cleared.")
        return

    print(f"[ask] {text}", flush=True)
    try:
        # send a "typing" indicator so the user knows we're working
        requests.post(f"{API}/sendChatAction",
                      json={"chat_id": chat_id, "action": "typing"}, timeout=5)
        answer = agent.ask(text, histories[chat_id])
        send(chat_id, answer)
        # trim history to prevent unbounded growth
        if len(histories[chat_id]) > 40:
            histories[chat_id] = agent.safe_trim(histories[chat_id], keep_last=20)
    except Exception:
        traceback.print_exc()
        send(chat_id, "Sorry, something broke. Try /reset if this keeps happening.")


def main() -> None:
    print(f"agent-bot ready. locked to chat_id={ALLOWED_CHAT_ID}", flush=True)
    offset = None
    while True:
        try:
            params = {"timeout": 25}
            if offset is not None:
                params["offset"] = offset
            r = requests.get(f"{API}/getUpdates", params=params, timeout=35)
            r.raise_for_status()
            updates = r.json().get("result", [])
            for u in updates:
                offset = u["update_id"] + 1
                msg = u.get("message") or u.get("edited_message")
                if msg:
                    handle_message(msg)
        except requests.exceptions.RequestException as e:
            print(f"[poll] transient network error: {e}", flush=True)
            time.sleep(3)
        except KeyboardInterrupt:
            print("shutting down", flush=True)
            sys.exit(0)
        except Exception:
            traceback.print_exc()
            time.sleep(5)


if __name__ == "__main__":
    main()