"""Bulk-load historical CSV data into Postgres (upi_tracker DB).

One-time script. Idempotent: drops+recreates the two tables. Run:
    pip install psycopg2-binary
    python3 import_history.py

Reads:
  - transactions_final.csv      (1,156 rows, full history)
  - counterparty_mappings.csv   (391 rows; only USER-sourced are imported;
                                 PENDING rows are skipped per plan)

Writes to Postgres (localhost:5433 / upi_tracker / user 'ayush'). Schema
matches what Spring Boot's JPA entities expect, plus two bonus columns
(description, source_file) that JPA doesn't know about — safe under
ddl-auto=update, since 'update' never drops columns.
"""
import csv
import os
import sys
import psycopg2

DB_HOST = os.getenv("PGHOST", "localhost")
DB_PORT = int(os.getenv("PGPORT", "5433"))
DB_NAME = os.getenv("PGDATABASE", "upi_tracker")
DB_USER = os.getenv("PGUSER", "ayush")
DB_PASSWORD = os.getenv("PGPASSWORD", "devpassword")

TXN_CSV = "transactions_final.csv"
MAP_CSV = "counterparty_mappings.csv"

for f in (TXN_CSV, MAP_CSV):
    if not os.path.exists(f):
        sys.exit(f"Missing {f} — must run from the project folder.")

conn = psycopg2.connect(
    host=DB_HOST, port=DB_PORT, dbname=DB_NAME,
    user=DB_USER, password=DB_PASSWORD,
)
conn.autocommit = False
cur = conn.cursor()

# ---- schema ---------------------------------------------------------
# Column names match Spring Boot's SpringPhysicalNamingStrategy conversion
# of the JPA entity fields (camelCase -> snake_case).
cur.execute("""
DROP TABLE IF EXISTS transactions CASCADE;
DROP TABLE IF EXISTS counterparty_mappings CASCADE;

CREATE TABLE transactions (
    id                BIGSERIAL PRIMARY KEY,
    date              DATE NOT NULL,
    direction         VARCHAR(16) NOT NULL,
    amount            NUMERIC(14,2) NOT NULL,
    payment_method    VARCHAR(32),
    counterparty_raw  TEXT,
    counterparty      TEXT,
    category          TEXT,
    reference         TEXT,
    balance           NUMERIC(14,2),
    description       TEXT,      -- extra: preserved from bank statement
    source_file       TEXT       -- extra: which PDF this row came from
);
CREATE INDEX idx_txn_date ON transactions(date);
CREATE INDEX idx_txn_cat  ON transactions(category);
CREATE INDEX idx_txn_cp   ON transactions(counterparty);
CREATE INDEX idx_txn_raw  ON transactions(counterparty_raw);

CREATE TABLE counterparty_mappings (
    raw_counterparty TEXT PRIMARY KEY,
    display_name     TEXT NOT NULL,
    category         TEXT,
    source           VARCHAR(16)   -- USER | LLM | MAPPED (no PENDING)
);
CREATE INDEX idx_map_display ON counterparty_mappings(display_name);
CREATE INDEX idx_map_cat     ON counterparty_mappings(category);
""")

# ---- transactions ---------------------------------------------------
txn_count = 0
with open(TXN_CSV, newline="") as f:
    reader = csv.DictReader(f)
    for r in reader:
        cur.execute("""
            INSERT INTO transactions
              (date, direction, amount, payment_method, counterparty_raw,
               counterparty, category, reference, balance,
               description, source_file)
            VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
        """, (
            r["date"], r["direction"], float(r["amount"]),
            r["payment_method"], r["counterparty_raw"], r["counterparty"],
            r["category"] or None, r["reference"] or None,
            float(r["balance"]) if r["balance"] else None,
            r["description"] or None, r["source_file"] or None,
        ))
        txn_count += 1

# ---- counterparty_mappings (USER only, PENDING purged) --------------
map_count = skipped = 0
with open(MAP_CSV, newline="") as f:
    reader = csv.DictReader(f)
    for r in reader:
        if r["source"] != "USER":
            skipped += 1
            continue
        cur.execute("""
            INSERT INTO counterparty_mappings
              (raw_counterparty, display_name, category, source)
            VALUES (%s, %s, %s, %s)
            ON CONFLICT (raw_counterparty) DO NOTHING
        """, (r["raw_counterparty"], r["display_name"],
              r["category"], r["source"]))
        map_count += 1

conn.commit()

# ---- verify ---------------------------------------------------------
cur.execute("SELECT MIN(date), MAX(date), COUNT(*) FROM transactions")
lo, hi, n = cur.fetchone()
print(f"transactions:          {n} rows, {lo} → {hi}")
cur.execute("SELECT COUNT(*) FROM counterparty_mappings")
print(f"counterparty_mappings: {cur.fetchone()[0]} rows imported")
print(f"                       ({skipped} PENDING rows skipped — as planned)")

cur.close()
conn.close()