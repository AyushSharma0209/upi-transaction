# Synthetic Sample Data

Two CSVs demonstrating the reconciliation matcher's input format. **All counterparties, references, and balances are fabricated — no real bank data.**

| File | Rows | Represents |
|---|---|---|
| `kotak_sample.csv` | 15 | Bank statement side (what Kotak's PDF would produce after `parse_unified.py`) |
| `ledger_sample.csv` | 14 | SMS-derived ledger side (what the Spring Boot pipeline would store in Postgres) |

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
  "statement_rows": 15,
  "ledger_rows": 14,
  "matched": 13,
  "statement_only": 2,
  "ledger_only": 1,
  "match_rate_pct": 86.7
}
```

## What the exceptions demonstrate

- **2 STATEMENT_ONLY** — Aug 22 ₹60 UPI to `DEMO PERSON B` (simulated SMS pipeline drop) and Aug 30 ₹500 CREDIT from `DEMO PERSON A` (simulated late arrival).
- **1 LEDGER_ONLY** — Aug 25 ₹150 `Zomato Wallet` (simulated wallet-internal operation that doesn't hit the bank statement — same pattern as the real production report shows).

This mirrors the shape of the real reconciliation output shown in the [live report](https://ayushsharma0209.github.io/upi-transaction/), so a judge can verify the matcher's logic end-to-end without needing real bank access.