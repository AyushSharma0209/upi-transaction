"""
eval_categorization.py — Panel A2 measurement

Runs May+June+July 2026 statement rows (truly unseen — not part of the
manual labeling corpus) through the local categorization service.
Aggregates the tier distribution.

Assumes:
  - Local Spring Boot backend running at http://localhost:8080
  - Local Postgres restored from prod snapshot
  - all_transactions.csv produced by parse_unified.py (already exists here)
"""

import csv
import json
import sys
import time
from collections import Counter
from datetime import datetime

import requests

# ---- Config ----
ENDPOINT      = "http://localhost:8080/api/eval/categorize"
CSV_PATH      = "all_transactions.csv"
TARGET_MONTHS = ["2026-05", "2026-06", "2026-07"]   # truly unseen
OUTPUT_PATH   = "eval_results.json"

# ---- Load + filter ----
with open(CSV_PATH) as f:
    all_rows = list(csv.DictReader(f))

subset = [r for r in all_rows if r["date"][:7] in TARGET_MONTHS]
print(f"Loaded {len(all_rows)} total rows; {len(subset)} in target months "
      f"({', '.join(TARGET_MONTHS)})\n")

if not subset:
    print("No rows matched target months. Check all_transactions.csv date format.")
    sys.exit(1)

# ---- Iterate ----
results, errors = [], []
tier_counts = Counter()
latencies   = {"MAPPED": [], "MERCHANT": [], "LLM": [], "PENDING": []}

for idx, row in enumerate(subset, 1):
    payload = {
        "counterparty":  row["counterparty"],
        "amount":        row["amount"],
        "paymentMethod": row["payment_method"],
    }
    t0 = time.time()
    try:
        resp = requests.post(ENDPOINT, json=payload, timeout=60)
        resp.raise_for_status()
        data = resp.json()
        elapsed_ms = (time.time() - t0) * 1000

        tier = data["source"]
        tier_counts[tier] += 1
        latencies.setdefault(tier, []).append(elapsed_ms)

        results.append({
            "date":         row["date"],
            "counterparty": row["counterparty"],
            "amount":       row["amount"],
            "source":       tier,
            "category":     data["category"],
            "confidence":   data.get("confidence"),
            "latency_ms":   round(elapsed_ms, 1),
        })
    except Exception as e:
        errors.append({"row": idx, "counterparty": row.get("counterparty"),
                       "error": str(e)})

    if idx % 25 == 0 or idx == len(subset):
        print(f"  [{idx:4d}/{len(subset)}]  "
              f"MAPPED={tier_counts['MAPPED']:3d}  "
              f"MERCHANT={tier_counts['MERCHANT']:3d}  "
              f"LLM={tier_counts['LLM']:3d}  "
              f"PENDING={tier_counts['PENDING']:3d}")

# ---- Summary ----
total = sum(tier_counts.values())
print("\n" + "=" * 68)
print(f"Panel A2 — categorization on {total} truly-unseen rows "
      f"(May+June+July 2026)")
print("=" * 68)
for tier in ["MAPPED", "MERCHANT", "LLM", "PENDING"]:
    n   = tier_counts.get(tier, 0)
    pct = 100 * n / total if total else 0
    lat = latencies.get(tier, [])
    avg = sum(lat)/len(lat) if lat else 0
    print(f"  {tier:10s}  {n:4d}  ({pct:5.1f}%)   avg {avg:6.1f} ms")

if errors:
    print(f"\n  {len(errors)} errored rows — see {OUTPUT_PATH}")

# ---- Save details for later report ----
with open(OUTPUT_PATH, "w") as f:
    json.dump({
        "generated_at":  datetime.now().isoformat(),
        "target_months": TARGET_MONTHS,
        "total_rows":    total,
        "tier_counts":   dict(tier_counts),
        "tier_avg_latency_ms": {
            t: (sum(l)/len(l) if l else 0) for t, l in latencies.items()
        },
        "results": results,
        "errors":  errors,
    }, f, indent=2)
print(f"\n→ Detailed results: {OUTPUT_PATH}")