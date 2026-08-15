"""Builds finance.db (SQLite) from transactions_final.csv + counterparty_mappings.csv.
Run once: python3 build_db.py
"""
import csv, sqlite3, os, sys

for f in ('transactions_final.csv', 'counterparty_mappings.csv'):
    if not os.path.exists(f):
        sys.exit(f"Missing {f} — put it in this folder first.")

conn = sqlite3.connect('finance.db')
c = conn.cursor()
c.executescript("""
DROP TABLE IF EXISTS transactions;
DROP TABLE IF EXISTS counterparty_mappings;
CREATE TABLE transactions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    date TEXT NOT NULL,
    description TEXT,
    reference TEXT,
    direction TEXT NOT NULL,
    amount REAL NOT NULL,
    payment_method TEXT,
    counterparty_raw TEXT,
    counterparty TEXT,
    category TEXT,
    balance REAL,
    source_file TEXT
);
CREATE TABLE counterparty_mappings (
    raw_counterparty TEXT PRIMARY KEY,
    display_name TEXT,
    category TEXT,
    source TEXT
);
CREATE INDEX idx_txn_date ON transactions(date);
CREATE INDEX idx_txn_cat ON transactions(category);
CREATE INDEX idx_txn_cp ON transactions(counterparty);
""")

with open('transactions_final.csv') as f:
    for r in csv.DictReader(f):
        c.execute("""INSERT INTO transactions
            (date,description,reference,direction,amount,payment_method,
             counterparty_raw,counterparty,category,balance,source_file)
            VALUES (?,?,?,?,?,?,?,?,?,?,?)""",
                  (r['date'], r['description'], r['reference'], r['direction'],
                   float(r['amount']), r['payment_method'], r['counterparty_raw'],
                   r['counterparty'], r['category'], float(r['balance']), r['source_file']))

with open('counterparty_mappings.csv') as f:
    for r in csv.DictReader(f):
        c.execute("INSERT INTO counterparty_mappings VALUES (?,?,?,?)",
                  (r['raw_counterparty'], r['display_name'], r['category'], r['source']))

conn.commit()
n = c.execute("SELECT COUNT(*) FROM transactions").fetchone()[0]
lo, hi = c.execute("SELECT MIN(date), MAX(date) FROM transactions").fetchone()
print(f"finance.db built: {n} transactions, {lo} to {hi}")
conn.close()