# Reconciliation Module

Python scripts that produce the numbers shown in the [live report](https://ayushsharma0209.github.io/upi-transaction/).

| File | Purpose |
|---|---|
| `parse_unified.py` | Kotak bank statement PDF → structured CSV (handles OLD and NEW Kotak formats) |
| `matcher.py` | Deterministic bucket matcher: statement rows ↔ ledger rows on (date, amount, direction, payment_method) |
| `run_reconciliation.py` | CLI: runs matcher for a date range, emits summary + JSON |
| `generate_report.py` | Renders the multi-panel HTML dashboard from computed results |
| `eval_categorization.py` | Panel A2: measures categorization pipeline effectiveness on unseen bank statement rows |

## Requirements
- Running PostgreSQL with the Spring Boot backend's schema
- `ANTHROPIC_API_KEY` env var for Panel A2 evaluation
- Kotak bank statement PDFs in `~/Desktop/pdfs-new/`

## Usage
Run scripts individually — see docstrings at the top of each file.
