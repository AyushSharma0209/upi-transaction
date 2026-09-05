"""
CLI: run reconciliation for a given date range.
Statement rows from all_transactions.csv (filtered by source_file + date range).
Ledger rows from local Postgres (filtered by date range).
Runs deterministic bucket matcher, prints summary + saves JSON.
"""

import argparse
import csv
import json

import psycopg2
from psycopg2.extras import RealDictCursor

from matcher import match


def load_statement(csv_path, source_file, start, end):
    rows = []
    with open(csv_path) as f:
        for r in csv.DictReader(f):
            if r["source_file"] != source_file:
                continue
            if r["date"] < start or r["date"] > end:
                continue
            rows.append({
                "date": r["date"],
                "amount": float(r["amount"]),
                "direction": r["direction"],
                "counterparty": r["counterparty"],
                "payment_method": r.get("payment_method"),
                "reference": r.get("reference") or None,
                "description": r.get("description") or "",
            })
    return rows


def load_ledger(db_url, start, end):
    conn = psycopg2.connect(db_url)
    cur = conn.cursor(cursor_factory=RealDictCursor)
    cur.execute("""
        SELECT date, direction, amount, counterparty, reference, description, payment_method
        FROM transactions
        WHERE date >= %s AND date <= %s
        ORDER BY date, id
    """, (start, end))
    rows = [{
        "date": r["date"].isoformat(),
        "amount": float(r["amount"]),
        "direction": r["direction"],
        "counterparty": r["counterparty"] or "",
        "payment_method": r["payment_method"],
        "reference": r["reference"],
        "description": r["description"] or "",
    } for r in cur.fetchall()]
    cur.close()
    conn.close()
    return rows


def summary(result):
    s = result["stats"]
    print(f"\n{'='*72}\nRECONCILIATION SUMMARY\n{'='*72}")
    print(f"  Statement rows:  {s['statement_rows']}")
    print(f"  Ledger rows:     {s['ledger_rows']}\n")
    print(f"  MATCHED:         {s['matched']:4d}  ({s['match_rate_pct']:5.1f}% of statement)")
    print(f"  STATEMENT_ONLY:  {s['statement_only']:4d}  (bank has, ledger missing)")
    print(f"  LEDGER_ONLY:     {s['ledger_only']:4d}  (ledger has, bank missing)")
    print(f"{'='*72}\n")

    if result["statement_only"]:
        print(f"STATEMENT_ONLY (first 20 of {len(result['statement_only'])}):")
        for r in result["statement_only"][:20]:
            print(f"  {r['date']} {r['direction']:6s} {r['amount']:>9.2f}  "
                  f"[{(r.get('payment_method') or '?'):8s}] {r['counterparty'][:35]}")
        print()

    if result["ledger_only"]:
        print(f"LEDGER_ONLY (first 20 of {len(result['ledger_only'])}):")
        for r in result["ledger_only"][:20]:
            print(f"  {r['date']} {r['direction']:6s} {r['amount']:>9.2f}  "
                  f"[{(r.get('payment_method') or '?'):8s}] {r['counterparty'][:35]}")


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--statement-csv", default="all_transactions.csv")
    p.add_argument("--statement-source", default="2026-august.pdf")
    p.add_argument("--start-date", default="2026-08-01")
    p.add_argument("--end-date", default="2026-08-31")
    p.add_argument("--db-url",
                   default="postgresql://ayush:devpassword@localhost:5433/upi_tracker")
    p.add_argument("--out", default="reconciliation_result.json")
    args = p.parse_args()

    print(f"Loading statement rows from {args.statement_csv} "
          f"(source_file={args.statement_source})...")
    stmt = load_statement(args.statement_csv, args.statement_source,
                          args.start_date, args.end_date)
    print(f"  {len(stmt)} statement rows")

    print(f"Loading ledger from Postgres ({args.start_date} → {args.end_date})...")
    ledger = load_ledger(args.db_url, args.start_date, args.end_date)
    print(f"  {len(ledger)} ledger rows\n")

    print("Running matcher...")
    result = match(stmt, ledger)
    summary(result)

    with open(args.out, "w") as f:
        json.dump(result, f, indent=2, default=str)
    print(f"\nDetailed results: {args.out}")


if __name__ == "__main__":
    main()