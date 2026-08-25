"""Telegram long-polling wrapper around agent.py + categorization callback handler.

Two independent poll loops on separate threads:
  1. @UPIAgent_bot         — text → agent.ask()
  2. Notification bot      — callback_query on approval buttons → Java /resolve-pending

Both bots hard-locked to ALLOWED_CHAT_ID.

Env vars:
  AGENT_TELEGRAM_BOT_TOKEN   chat/agent bot from @BotFather
  TELEGRAM_BOT_TOKEN         notification bot (also used by Java for pushes)
  ALLOWED_CHAT_ID            your Telegram numeric chat id
  APP_URL                    default http://upi-tracker:8080 (prod); set to
                             http://host.docker.internal:8080 when testing on Mac
  ANTHROPIC_API_KEY          inherited by agent.py
  PG*                        inherited by agent.py

Run:  python3 agent_bot.py
"""
import os
import sys
import time
import threading
import traceback
from collections import defaultdict

import requests

import agent  # sets up DB conn & builds SYSTEM prompt on import

AGENT_TOKEN     = os.environ["AGENT_TELEGRAM_BOT_TOKEN"]
NOTIF_TOKEN     = os.environ["TELEGRAM_BOT_TOKEN"]
ALLOWED_CHAT_ID = int(os.environ["ALLOWED_CHAT_ID"])
APP_URL         = os.environ.get("APP_URL", "http://upi-tracker:8080")

AGENT_API = f"https://api.telegram.org/bot{AGENT_TOKEN}"
NOTIF_API = f"https://api.telegram.org/bot{NOTIF_TOKEN}"

histories: dict[int, list] = defaultdict(list)
agent.SYSTEM = agent.build_system_prompt()


# ---------------------------------------------------------------- helpers

def _chunks(s: str, n: int):
    for i in range(0, len(s), n):
        yield s[i:i + n]


def send(api_base: str, chat_id: int, text: str) -> None:
    for chunk in _chunks(text, 4000):
        try:
            requests.post(
                f"{api_base}/sendMessage",
                json={"chat_id": chat_id, "text": chunk, "parse_mode": "Markdown"},
                timeout=15,
            )
        except Exception as e:
            print(f"[send] failed: {e}", flush=True)


# ---------------------------------------------------------------- agent (chat) handler

def handle_agent_message(msg: dict) -> None:
    chat_id = msg.get("chat", {}).get("id")
    text = (msg.get("text") or "").strip()

    if chat_id != ALLOWED_CHAT_ID:
        print(f"[agent-reject] chat_id={chat_id}", flush=True)
        return
    if not text:
        return

    if text.lower() in ("/start", "/help"):
        send(AGENT_API, chat_id,
             "Hi Ayush. Ask me anything about your finances.\n\n"
             "Examples:\n"
             "• how much on food in march?\n"
             "• total sent to abhishek this year?\n"
             "• biggest expense last week?\n"
             "• what's my current balance?")
        return

    if text.lower() == "/reset":
        histories[chat_id] = []
        send(AGENT_API, chat_id, "History cleared.")
        return

    print(f"[ask] {text}", flush=True)
    try:
        requests.post(f"{AGENT_API}/sendChatAction",
                      json={"chat_id": chat_id, "action": "typing"}, timeout=5)
        answer = agent.ask(text, histories[chat_id])
        send(AGENT_API, chat_id, answer)
        if len(histories[chat_id]) > 40:
            histories[chat_id] = agent.safe_trim(histories[chat_id], keep_last=20)
    except Exception:
        traceback.print_exc()
        send(AGENT_API, chat_id, "Sorry, something broke. Try /reset if this keeps happening.")


# ---------------------------------------------------------------- callback handler

def handle_callback(cq: dict) -> None:
    cq_id   = cq.get("id")
    chat_id = cq.get("message", {}).get("chat", {}).get("id")
    data    = cq.get("data", "")

    if chat_id != ALLOWED_CHAT_ID:
        print(f"[callback-reject] chat_id={chat_id}", flush=True)
        return

    # Acknowledge tap immediately (removes the loading spinner on your phone)
    try:
        requests.post(f"{NOTIF_API}/answerCallbackQuery",
                      json={"callback_query_id": cq_id}, timeout=5)
    except Exception as e:
        print(f"[ack] failed: {e}", flush=True)

    # Parse "pcat:<pending_id>:<code>"
    parts = data.split(":")
    if len(parts) != 3 or parts[0] != "pcat":
        print(f"[callback] unknown payload: {data}", flush=True)
        return
    try:
        pending_id = int(parts[1])
    except ValueError:
        print(f"[callback] bad pending_id: {parts[1]}", flush=True)
        return
    code = parts[2]

    print(f"[callback] pcat pending={pending_id} code={code}", flush=True)

    try:
        r = requests.post(
            f"{APP_URL}/internal/resolve-pending",
            json={"pending_id": pending_id, "category_code": code},
            timeout=10,
        )
        print(f"[resolve] {r.status_code} {r.text[:200]}", flush=True)
    except Exception as e:
        print(f"[resolve] failed: {e}", flush=True)


# ---------------------------------------------------------------- poll loops

def poll_loop(name: str, api_base: str, handler_msg, handler_cb):
    print(f"[{name}] poll ready", flush=True)
    offset = None
    while True:
        try:
            params = {"timeout": 25}
            if offset is not None:
                params["offset"] = offset
            r = requests.get(f"{api_base}/getUpdates", params=params, timeout=35)
            r.raise_for_status()
            for u in r.json().get("result", []):
                offset = u["update_id"] + 1
                if handler_msg and (msg := u.get("message") or u.get("edited_message")):
                    handler_msg(msg)
                if handler_cb and (cq := u.get("callback_query")):
                    handler_cb(cq)
        except requests.exceptions.RequestException as e:
            print(f"[{name}] transient error: {e}", flush=True)
            time.sleep(3)
        except Exception:
            traceback.print_exc()
            time.sleep(5)


def main():
    print(f"agent-bot ready. locked to chat_id={ALLOWED_CHAT_ID}", flush=True)
    print(f"  APP_URL = {APP_URL}", flush=True)

    threading.Thread(
        target=poll_loop,
        args=("agent", AGENT_API, handle_agent_message, None),
        daemon=True,
    ).start()
    threading.Thread(
        target=poll_loop,
        args=("notif", NOTIF_API, None, handle_callback),
        daemon=True,
    ).start()

    try:
        while True:
            time.sleep(60)
    except KeyboardInterrupt:
        print("shutting down", flush=True)
        sys.exit(0)


if __name__ == "__main__":
    main()