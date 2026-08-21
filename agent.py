"""Personal finance agent — natural language over your transaction database.

Run:  python3 agent.py          (interactive chat in terminal)
Needs: Postgres running (upi_tracker DB) and ANTHROPIC_API_KEY exported.

Architecture: Claude Sonnet in a tool-use loop. Two tools:
  run_sql               — read-only SQL against the database
  search_counterparties — fuzzy name lookup so the agent never guesses spellings
The system prompt is built FROM the database at startup (categories, top
counterparties, date range), so it stays accurate as data grows.
"""
import json
import os
from datetime import date

import anthropic
import psycopg2
from psycopg2.extras import RealDictCursor

MODEL = "claude-sonnet-4-6"
MAX_TURNS = 12          # max reasoning iterations per question

DB_HOST = os.getenv("PGHOST", "localhost")
DB_PORT = int(os.getenv("PGPORT", "5433"))
DB_NAME = os.getenv("PGDATABASE", "upi_tracker")
DB_USER = os.getenv("PGUSER", "ayush")
DB_PASSWORD = os.getenv("PGPASSWORD", "devpassword")

client = anthropic.Anthropic()
conn = psycopg2.connect(
    host=DB_HOST, port=DB_PORT, dbname=DB_NAME,
    user=DB_USER, password=DB_PASSWORD,
    cursor_factory=RealDictCursor,
)
conn.autocommit = True


# ---------------------------------------------------------------- tools

def run_sql(sql: str) -> str:
    """Execute read-only SQL. Returns rows as JSON, or the error message."""
    lowered = sql.strip().lower()
    if not lowered.startswith('select') and not lowered.startswith('with'):
        return "ERROR: only SELECT queries are allowed."
    for banned in ('insert', 'update', 'delete', 'drop', 'alter', 'create',
                   'attach', 'copy', 'grant', 'revoke'):
        if f' {banned} ' in f' {lowered} ':
            return f"ERROR: '{banned}' is not allowed."
    try:
        with conn.cursor() as cur:
            cur.execute(sql)
            rows = cur.fetchmany(200)
            result = [dict(r) for r in rows]
        return json.dumps(result, default=str) if result else "[] (no rows)"
    except Exception as e:
        try:
            conn.rollback()
        except Exception:
            pass
        return f"SQL ERROR: {e}"


def search_counterparties(fragment: str) -> str:
    """Fuzzy search over canonical and raw counterparty names."""
    like = f"%{fragment}%"
    with conn.cursor() as cur:
        cur.execute("""
            SELECT counterparty, category, COUNT(*) AS txns,
                   ROUND(SUM(CASE WHEN direction='DEBIT' THEN amount ELSE 0 END)) AS total_paid,
                   ROUND(SUM(CASE WHEN direction='CREDIT' THEN amount ELSE 0 END)) AS total_received
            FROM transactions
            WHERE counterparty ILIKE %s
               OR counterparty_raw ILIKE %s
            GROUP BY counterparty, category
            ORDER BY txns DESC LIMIT 15
        """, (like, like))
        rows = cur.fetchall()
    if not rows:
        return f"No counterparties matching '{fragment}'."
    return json.dumps([dict(r) for r in rows], default=str)


TOOLS = [
    {
        "name": "run_sql",
        "description": (
            "Run a read-only SQL SELECT against the finance database. "
            "Use this for all aggregations, filters, trends, and lookups. "
            "PostgreSQL dialect. Dates are DATE type: filter with "
            "date >= '2026-03-01' AND date < '2026-04-01', or "
            "to_char(date,'YYYY-MM') = '2026-03'. "
            "Month grouping: to_char(date,'YYYY-MM'). "
            "Case-insensitive LIKE: use ILIKE. Max 200 rows returned."
        ),
        "input_schema": {
            "type": "object",
            "properties": {"sql": {"type": "string"}},
            "required": ["sql"],
        },
    },
    {
        "name": "search_counterparties",
        "description": (
            "Fuzzy-search counterparty names (case-insensitive, searches both "
            "canonical and raw bank names). ALWAYS use this first when the user "
            "mentions a person/merchant, before writing SQL with that name — "
            "never guess exact spellings."
        ),
        "input_schema": {
            "type": "object",
            "properties": {"fragment": {"type": "string"}},
            "required": ["fragment"],
        },
    },
]

TOOL_FNS = {"run_sql": lambda i: run_sql(i["sql"]),
            "search_counterparties": lambda i: search_counterparties(i["fragment"])}


# ------------------------------------------- system prompt built from the DB

def build_system_prompt() -> str:
    with conn.cursor() as cur:
        cur.execute("SELECT MIN(date) AS lo, MAX(date) AS hi, COUNT(*) AS n FROM transactions")
        r = cur.fetchone()
        lo, hi, n = r["lo"], r["hi"], r["n"]
        cur.execute("""
            SELECT category, COUNT(*) AS n,
                   ROUND(SUM(CASE WHEN direction='DEBIT' THEN amount ELSE 0 END)) AS spent
            FROM transactions GROUP BY category ORDER BY n DESC""")
        cats = cur.fetchall()
        cur.execute("""
            SELECT counterparty, category, COUNT(*) AS n FROM transactions
            GROUP BY counterparty, category ORDER BY n DESC LIMIT 40""")
        top = cur.fetchall()

    cat_lines = "\n".join(f"  - {c['category']}: {c['n']} txns, ₹{c['spent']:,.0f} spent"
                          for c in cats)
    top_lines = "\n".join(f"  - {t['counterparty']} ({t['category']}, {t['n']} txns)"
                          for t in top)

    return f"""You are Ayush's personal financial analyst with direct database access.
Today's date: {date.today().isoformat()}.

DATA: {n} transactions from {lo} to {hi}, parsed from Kotak bank statements and
validated against the bank's own running balance (provably complete for that range).

SCHEMA — table `transactions` (PostgreSQL):
  date (DATE), description, reference, direction ('DEBIT'=money out,
  'CREDIT'=money in), amount (NUMERIC, always positive; direction gives the sign),
  payment_method (UPI/CARD/NACH/NEFT/CHARGE/INTEREST/...),
  counterparty_raw (exact bank string), counterparty (clean canonical name),
  category, balance (account balance AFTER this transaction), source_file.
Table `counterparty_mappings`: raw_counterparty, display_name, category, source.

CATEGORIES (with actual usage):
{cat_lines}

TOP COUNTERPARTIES:
{top_lines}

SEMANTIC RULES:
- "Spending" usually means direction='DEBIT'. When a question is about lifestyle
  spending, consider whether Investments (SIPs), Family transfers, and Bank Charges
  should be excluded — state your inclusion choice in one short phrase.
- 'Family'/'Friends' categories are money transfers to people, not purchases.
- 'Uncategorized' covers some txns (mostly small one-time UPI payments). When
  totals matter, mention if Uncategorized could affect the answer.
- The `balance` column lets you reconstruct account balance at any point in time
  (take balance of the latest transaction <= that date).
- Current balance in this DB = balance of the most recent transaction, but note
  the data ends {hi}; say so if asked about "now".

POSTGRES DIALECT NOTES:
- Case-insensitive LIKE is ILIKE.
- Month grouping / month filter: to_char(date,'YYYY-MM').
- Date range filters: date >= '2026-03-01' AND date < '2026-04-01'.

METHOD:
1. For questions about "most recent", "latest", "last", "today", "this week",
   "this month" — ALWAYS execute a fresh SQL query (ORDER BY date DESC, or
   date filter as appropriate). NEVER answer from data seen in earlier turns
   or from the counterparty snapshot in this prompt. Recency questions must
   hit the database.
2. If the question names a person/merchant, call search_counterparties FIRST.
3. Write focused SQL; chain multiple queries for multi-part questions.
4. If a query returns empty, DO NOT conclude "no data" — search for the entity,
   check the date range, then retry.
5. Ground every number in query results. Never invent figures.
6. Answer conversationally and concisely: lead with the number, add one or two
   insights (comparisons, trends, anomalies) when genuinely useful. Use ₹ with
   Indian comma formatting.
"""


# ---------------------------------------------------------------- agent loop

def safe_trim(history, keep_last=20):
    """Trim history, but only cut at a clean question boundary —
    never between a tool_use and its tool_result."""
    if len(history) <= keep_last:
        return history
    for i in range(len(history) - keep_last, len(history)):
        msg = history[i]
        if msg["role"] == "user" and isinstance(msg["content"], str):
            return history[i:]
    return history


def ask(question: str, history: list) -> str:
    history.append({"role": "user", "content": question})
    for _ in range(MAX_TURNS):
        resp = client.messages.create(
            model=MODEL,
            max_tokens=1500,
            system=SYSTEM,
            tools=TOOLS,
            messages=history,
        )
        if resp.stop_reason == "tool_use":
            history.append({"role": "assistant", "content": resp.content})
            results = []
            for block in resp.content:
                if block.type == "tool_use":
                    print(f"   ⚙ {block.name}({json.dumps(block.input)[:110]})")
                    out = TOOL_FNS[block.name](block.input)
                    results.append({"type": "tool_result",
                                    "tool_use_id": block.id,
                                    "content": out})
            history.append({"role": "user", "content": results})
        else:
            text = "".join(b.text for b in resp.content if b.type == "text")
            history.append({"role": "assistant", "content": resp.content})
            return text
    return "(Stopped: too many reasoning steps — try a narrower question.)"


if __name__ == "__main__":
    SYSTEM = build_system_prompt()
    print("Finance agent ready. Ask in plain language (or 'quit').")
    print("Examples: how much on food in March? · who do I pay most often? ·")
    print("          did my spending go up after January? · biggest single expense?\n")
    history = []
    while True:
        try:
            q = input("you> ").strip()
        except (EOFError, KeyboardInterrupt):
            break
        if not q or q.lower() in ("quit", "exit"):
            break
        try:
            answer = ask(q, history)
            print(f"\n{answer}\n")
        except anthropic.APIError as e:
            print(f"API error: {e}\n")
        if len(history) > 40:
            history = safe_trim(history, keep_last=20)