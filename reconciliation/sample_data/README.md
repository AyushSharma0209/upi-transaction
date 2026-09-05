# Synthetic Sample Data

Two CSVs demonstrating the reconciliation matcher's input format at realistic scale. **All counterparties, references, and balances are fabricated — no real bank data.**

| File | Rows | Represents |
|---|---|---|
| `kotak_sample.csv` | 60 | Bank statement side (what Kotak's PDF would produce after `parse_unified.py`) |
| `ledger_sample.csv` | 57 | SMS-derived ledger side (what the Spring Boot pipeline would store in Postgres) |

Covers 25 days of activity (Aug 1–25, 2026) across food delivery, groceries, transport, subscriptions, bills, fuel, health, salary credit, NACH SIP mandates, and P2P transfers — the same shape as the real production ledger.

## Quick run

From the `reconciliation/` folder:

```bash
pip install -r requirements.txt
python -c "from matcher import match; import csv, json; \
  stmt = [dict(r, amount=float(r['amount'])) for r in csv.DictReader(open('sample_data/kotak_sample.csv'))]; \
  ledger = [dict(r, amount=float(r['amount'])) for r in csv.DictReader(open('sample_data/ledger_sample.csv'))]; \
  print(json.dumps(match(stmt, ledger)['stats'], indent=2))"
```

## Expected output

```json
{
  "statement_rows": 60,
  "ledger_rows": 57,
  "matched": 55,
  "statement_only": 5,
  "ledger_only": 2,
  "match_rate_pct": 91.7
}
```

## What the exceptions demonstrate

- **5 STATEMENT_ONLY** — Aug 6 ₹200 DEMO PERSON B, Aug 11 ₹78 UBER, Aug 16 ₹189 SWIGGY, Aug 17 ₹456 ZEPTO, Aug 23 ₹249 AIRTEL. These simulate the two failure modes the real pipeline encounters: SMS delivery drops, and periods when the Gemini parser fallback is unavailable.
- **2 LEDGER_ONLY** — Aug 15 ₹250 `Zomato Wallet` and Aug 27 ₹150 `Amazon Pay Balance`. These simulate wallet-internal operations that legitimately don't hit the bank statement — the same pattern the real production report shows in Panel B.

This mirrors the exception shape of the real reconciliation output in the [live report](https://ayushsharma0209.github.io/upi-transaction/), so a judge can verify the matcher's logic end-to-end at realistic scale without needing real bank access.