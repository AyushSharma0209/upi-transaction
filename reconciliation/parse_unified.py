import pdfplumber
import csv
import glob
import os
from datetime import datetime
from collections import Counter

# ---------- shared helpers ----------

def clean_text(s):
    if s is None:
        return ''
    return ' '.join(str(s).replace('\n', ' ').split())

def clean_amount(s):
    """Handles '2,000.00', '-590.00', '+20,000.00', '-', '' -> float or None"""
    if not s:
        return None
    s = str(s).replace('\n', '').replace(',', '').replace('+', '').strip()
    if s in ('-', ''):
        return None
    try:
        return float(s)
    except ValueError:
        return None

def parse_description(desc):
    d = desc.strip()
    if d.startswith('UPIM/'):
        parts = d.split('/')
        return 'UPI_MANDATE', clean_text(parts[1]) if len(parts) > 1 else d
    if d.startswith('UPI/'):
        parts = d.split('/')
        return 'UPI', clean_text(parts[1]) if len(parts) > 1 else d
    if d.startswith(('PCI/', 'PCD/')):
        parts = d.split('/')
        return 'CARD', clean_text(parts[2]) if len(parts) > 2 else d
    if d.startswith('NACH'):
        return 'NACH', 'Indian Clearing Corp (SIP/Mandate)'
    if d.startswith(('Chrg:', 'CHRG:', 'REM- Chrg:', 'REM-')):
        return 'CHARGE', clean_text(d)[:60]
    if d.startswith('Int.Pd'):
        return 'INTEREST', 'Bank Interest'
    if d.startswith('VISA-REFUND') or d.startswith('REV-'):
        return 'REFUND', clean_text(d)[:60]
    if 'CASH DEPOSIT' in d.upper():
        return 'CASH', 'Cash Deposit'
    if d.startswith('NEFT'):
        # NEFT YESF352143005547 ZERODHA BROKING LTD DSCNB A
        words = clean_text(d).split()
        cp = ' '.join(words[2:5]) if len(words) > 2 else 'NEFT Transfer'
        return 'NEFT', cp
    if d.startswith(('IMPS', 'RTGS')):
        return d[:4], clean_text(d)[:60]
    return 'OTHER', clean_text(d)[:60]

def parse_date(s):
    s = clean_text(s).replace(',', '')     # '01 Aug, 2025' -> '01 Aug 2025'
    return datetime.strptime(s, '%d %b %Y').date().isoformat()

# ---------- format detection ----------

def detect_format(header):
    h = [clean_text(c).upper() for c in header]
    if any('WITHDRAWAL' in c for c in h):
        return 'NEW'
    if 'DEBIT' in h and 'CREDIT' in h:
        return 'OLD'
    return None

# ---------- per-format row handlers ----------

def rows_new(table, state):
    """NEW: ['#','Date','Description','Chq/Ref','Withdrawal','Deposit','Balance']"""
    out = []
    for row in table:
        if len(row) < 7:
            continue
        num = clean_text(row[0])
        if num == '-' and 'Opening Balance' in clean_text(row[2]):
            state['opening'] = clean_amount(row[6])
            continue
        if not num.isdigit():
            continue
        w, d = clean_amount(row[4]), clean_amount(row[5])
        out.append(make_txn(parse_date(row[1]), clean_text(row[2]), clean_text(row[3]),
                            'DEBIT' if w else 'CREDIT', w if w else d, clean_amount(row[6])))
    return out

def rows_old(table, state):
    """OLD: ['DATE','TRANSACTION DETAILS','CHEQUE/REFERENCE#','DEBIT','CREDIT','BALANCE']"""
    out = []
    for row in table:
        if len(row) < 6:
            continue
        date_cell = clean_text(row[0])
        desc = clean_text(row[1])
        if date_cell.upper() == 'DATE':          # header
            continue
        if 'OPENING BALANCE' in desc.upper():
            state['opening'] = clean_amount(row[5])
            continue
        if not date_cell:
            continue
        debit, credit = clean_amount(row[3]), clean_amount(row[4])
        if debit is None and credit is None:
            continue
        out.append(make_txn(parse_date(date_cell), desc, clean_text(row[2]),
                            'DEBIT' if debit else 'CREDIT',
                            debit if debit else credit, clean_amount(row[5])))
    return out

def make_txn(date, desc, ref, direction, amount, balance):
    method, counterparty = parse_description(desc)
    return {
        'date': date, 'description': desc, 'reference': ref or None,
        'direction': direction, 'amount': amount,
        'payment_method': method, 'counterparty': counterparty, 'balance': balance,
    }

# ---------- file-level parse + validate ----------

def parse_file(path):
    txns, state = [], {'opening': None}
    summary_closing = None
    with pdfplumber.open(path) as pdf:
        for page in pdf.pages:
            for table in page.extract_tables():
                if not table:
                    continue
                # Account Summary (new format only)
                head0 = clean_text(table[0][0]) if table[0] else ''
                if 'Account Summary' in head0:
                    for row in table:
                        if row[0] and 'Savings Account' in str(row[0]):
                            summary_closing = clean_amount(row[2])
                    continue
                # find the header row (row 0 or row 1 depending on title row)
                fmt = None
                for probe in table[:3]:
                    fmt = detect_format(probe)
                    if fmt:
                        break
                # FALLBACK: headerless continuation chunk -> detect by row shape
                if fmt is None:
                    import re as _re
                    for r in table:
                        c0 = clean_text(r[0]) if r and r[0] is not None else ''
                        if len(r) >= 7 and c0.isdigit():
                            fmt = 'NEW'
                            break
                        if len(r) >= 6 and _re.match(r'\d{1,2} [A-Za-z]{3},? \d{4}', c0):
                            fmt = 'OLD'
                            break
                if fmt == 'NEW':
                    txns.extend(rows_new(table, state))
                elif fmt == 'OLD':
                    txns.extend(rows_old(table, state))
    return txns, state['opening'], summary_closing

def validate_file(name, txns, opening, summary_closing):
    if opening is None or not txns:
        print(f"  [{name}] cannot validate (no opening balance or no rows)")
        return False
    errors, prev = 0, opening
    for t in txns:   # statement order preserved
        expected = prev - t['amount'] if t['direction'] == 'DEBIT' else prev + t['amount']
        if abs(expected - t['balance']) > 0.01:
            print(f"  [{name}] MISMATCH {t['date']} {t['counterparty']}: "
                  f"expected {expected:.2f}, got {t['balance']:.2f}")
            errors += 1
        prev = t['balance']
    status = f"checksum {len(txns)-errors}/{len(txns)}"
    if summary_closing is not None:
        ok = abs(txns[-1]['balance'] - summary_closing) < 0.01
        status += f", closing {'MATCH' if ok else 'MISMATCH'}"
    print(f"  [{name}] {status}")
    return errors == 0

# ---------- main ----------

if __name__ == '__main__':
    folders = [os.path.expanduser('~/Desktop/pdfs-old'),
               os.path.expanduser('~/Desktop/pdfs-new')]
    files = []
    for f in folders:
        files.extend(sorted(glob.glob(os.path.join(f, '*.pdf'))))

    print(f"Found {len(files)} PDFs\n")
    all_txns, all_ok = [], True

    for path in files:
        name = os.path.basename(path)
        try:
            txns, opening, closing = parse_file(path)
            print(f"{name}: {len(txns)} transactions")
            ok = validate_file(name, txns, opening, closing)
            all_ok = all_ok and ok
            for t in txns:
                t['source_file'] = name
            all_txns.extend(txns)
        except Exception as e:
            print(f"{name}: FAILED — {e}")
            all_ok = False

    all_txns.sort(key=lambda t: t['date'])

    with open('all_transactions.csv', 'w', newline='') as f:
        w = csv.DictWriter(f, fieldnames=['date', 'description', 'reference', 'direction',
                                          'amount', 'payment_method', 'counterparty',
                                          'balance', 'source_file'])
        w.writeheader()
        w.writerows(all_txns)

    counts = Counter(t['counterparty'] for t in all_txns)
    with open('counterparties.csv', 'w', newline='') as f:
        w = csv.writer(f)
        w.writerow(['counterparty', 'txn_count', 'category'])
        for name, count in counts.most_common():
            w.writerow([name, count, ''])

    print(f"\n{'='*55}")
    print(f"TOTAL: {len(all_txns)} transactions -> all_transactions.csv")
    print(f"All files validated: {'YES' if all_ok else 'NO — see mismatches above'}")
    print(f"Distinct counterparties: {len(counts)} -> counterparties.csv")
