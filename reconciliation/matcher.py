"""
Deterministic bucket-based reconciliation matcher.
Match key: (date, amount, direction, payment_method).
No fuzzy scoring — same key = matchable pair.
"""

from collections import defaultdict


def bucket_key(row):
    return (
        row["date"],
        round(float(row["amount"]), 2),
        row["direction"],
        row.get("payment_method") or "",
    )


def match(statement_rows, ledger_rows):
    stmt_buckets = defaultdict(list)
    for s in statement_rows:
        stmt_buckets[bucket_key(s)].append(s)

    ledger_buckets = defaultdict(list)
    for l in ledger_rows:
        ledger_buckets[bucket_key(l)].append(l)

    matched = []
    statement_only = []
    ledger_only = []

    all_keys = set(stmt_buckets.keys()) | set(ledger_buckets.keys())
    for k in all_keys:
        s_bucket = stmt_buckets.get(k, [])
        l_bucket = ledger_buckets.get(k, [])
        n = min(len(s_bucket), len(l_bucket))

        for i in range(n):
            matched.append({"statement": s_bucket[i], "ledger": l_bucket[i]})

        for s in s_bucket[n:]:
            statement_only.append(s)
        for l in l_bucket[n:]:
            ledger_only.append(l)

    stats = {
        "statement_rows": len(statement_rows),
        "ledger_rows": len(ledger_rows),
        "matched": len(matched),
        "statement_only": len(statement_only),
        "ledger_only": len(ledger_only),
        "match_rate_pct": round(100 * len(matched) / len(statement_rows), 1)
        if statement_rows else 0.0,
    }

    return {
        "matched": matched,
        "statement_only": statement_only,
        "ledger_only": ledger_only,
        "stats": stats,
    }