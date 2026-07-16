"""
Flexible ingestion so real uploaded data works, not just our synthetic file.

Collections exports never share a schema. This module maps arbitrary column
names onto Recoup's canonical fields using a synonym dictionary, coerces types,
and fills sensible defaults for anything missing -- reporting exactly what it
found so nothing is silently fabricated.
"""

import csv
import io
import re
from datetime import datetime

# canonical_field -> list of accepted source-column synonyms (lowercased, we
# also strip non-alphanumerics before matching so "Past Due Amount" == "past_due_amount").
SYNONYMS = {
    "account_id": ["account_id", "loan_id", "id", "accountnumber", "loanid", "reference",
                   "agreementno", "agreement", "agreementnumber", "agreementid", "loanaccountno"],
    "region": ["region", "area", "zone", "branch", "branch_name", "branchname", "territory"],
    "geo_x": ["geo_x", "x", "longitude", "lon", "lng"],
    "geo_y": ["geo_y", "y", "latitude", "lat"],
    "age": ["age"],
    "occupation": ["occupation", "job", "employment"],
    "marital_status": ["marital_status", "marital", "maritalstatus"],
    "customer_tenure_years": ["customer_tenure_years", "tenure", "customertenure"],
    "months_on_book": ["months_on_book", "mob", "mob_status", "mobstatus", "monthsonbook"],
    "num_co_borrowers": ["num_co_borrowers", "coborrowers", "cosigners", "guarantors"],
    "lent_amount": ["lent_amount", "loanamount", "principal", "disbursedamount"],
    "lent_date": ["lent_date", "disbursaldate", "disburseddate", "disbursementdate", "loandate"],
    "maturity_date": ["maturity_date", "maturitydate"],
    "interest": ["interest", "interestrate", "rate", "apr"],
    "term_months": ["term_months", "term", "tenor", "loanterm"],
    "total_balance": ["total_balance", "balance", "outstanding", "totaloutstanding",
                      "pos", "principaloutstanding", "principal_outstanding", "outstandingprincipal"],
    "next_installment_amount": ["next_installment_amount", "nextinstallment", "emi", "emiamount",
                                "emidue", "installment", "installmentamount", "instalment", "instalmentamount",
                                "nextduamount", "nextemi", "emiamt"],
    "past_due_amount": ["past_due_amount", "pastdue", "overdue", "arrears", "amountoverdue", "overdueamount",
                        "overdueamt", "totaloverdue", "totalod", "odamount", "od_amount", "emioverdue", "emiod",
                        "principaloverdue", "arrearamount", "totalarrear", "posoverdue"],
    "current_bucket": ["current_bucket", "bucket", "bucketname", "delinquencybucket", "dpdbucket", "riskbucket"],
    "days_past_due": ["days_past_due", "dpd", "dpddays", "noofdpd", "dayspastdue", "dayoverdue"],
    "last_payment_date": ["last_payment_date", "lmpd", "lastpaymentdate", "lastmonthpaiddate", "lastemipaiddate", "lpd"],
    "instrument_type": ["instrument_type", "instrumenttype", "instrument"],
    "mandate_status": ["mandate_status", "mandatestatus", "nachstatus"],
    "presentation_status": ["presentation_status", "presentationstatus", "emistatus", "presentstatus"],
    "bounce_reason": ["bounce_reason", "reason_description", "reasondescription", "returnreason", "bouncereason"],
    "payment_type": ["payment_type", "current_month_payment_type", "currentmonthpaymenttype", "paymenttype"],
    "mode_of_payment": ["mode_of_payment", "current_month_mode_of_payment", "currentmonthmodeofpayment", "modeofpayment"],
    "paid_by": ["paid_by", "paidby"],
    "avg_pay_amount_ever": ["avg_pay_amount_ever", "avgpayment", "averagepayment"],
    "last_pay_amount": ["last_pay_amount", "lastpayment", "lastpaidamount", "lastreceiptamount",
                        "lastemipaid", "lastpaidamt", "lastpaymentamount"],
    "days_since_last_payment": ["days_since_last_payment", "dayssincepayment", "dayssincelastpay"],
    "num_payments_12m": ["num_payments_12m", "payments12m", "numpayments"],
    "pay_ratio_6m": ["pay_ratio_6m", "payratio", "paymentratio"],
    "std_pay_amount": ["std_pay_amount", "stdpayment"],
    "contact_attempts_6m": ["contact_attempts_6m", "contacts", "contactattempts", "callattempts"],
    "rpc_rate": ["rpc_rate", "rpc", "rightpartycontact", "contactrate"],
    "promises_made_6m": ["promises_made_6m", "promises", "ptpcount", "promisesmade"],
    "promises_kept_6m": ["promises_kept_6m", "promiseskept", "keptpromises"],
    "refusals_6m": ["refusals_6m", "refusals", "disputes"],
    "days_since_last_contact": ["days_since_last_contact", "dayssincecontact"],
    "preferred_channel": ["preferred_channel", "channel", "bestchannel"],
    "best_contact_hour": ["best_contact_hour", "besthour", "contacthour"],
    "label_promise_to_pay": ["label_promise_to_pay", "promise_to_pay", "ptp", "labelptp"],
    "label_actual_payment": ["label_actual_payment", "actual_payment", "paid", "labelapp", "didpay"],
}

NUMERIC = {
    "geo_x", "geo_y", "age", "customer_tenure_years", "months_on_book", "num_co_borrowers",
    "lent_amount", "interest", "term_months", "total_balance",
    "next_installment_amount", "past_due_amount", "current_bucket", "days_past_due",
    "avg_pay_amount_ever", "last_pay_amount", "days_since_last_payment",
    "num_payments_12m", "pay_ratio_6m", "std_pay_amount", "contact_attempts_6m",
    "rpc_rate", "promises_made_6m", "promises_kept_6m", "refusals_6m",
    "days_since_last_contact", "best_contact_hour",
    "label_promise_to_pay", "label_actual_payment",
}

# Fields that arrive as dates and are turned into numeric features by derive().
DATE_FIELDS = {"last_payment_date", "lent_date", "maturity_date"}

# Words in presentation/mandate/reason status columns that signal a failed
# collection (bounce / return / inactive mandate). Used only as a soft behavioral
# proxy when no explicit payment-history features are present.
_NEG_STATUS = ("bounce", "return", "reject", "fail", "unpaid", "insuffic",
               "dishonour", "dishonor", "inactive", "cancel", "npa", "overdue")
_POS_STATUS = ("paid", "success", "clear", "present", "active", "honour", "honor", "collected")

_DATE_FORMATS = ("%Y-%m-%d", "%d-%m-%Y", "%d/%m/%Y", "%m/%d/%Y", "%Y/%m/%d",
                 "%d-%b-%Y", "%d-%b-%y", "%d.%m.%Y", "%Y-%m-%d %H:%M:%S", "%d-%m-%Y %H:%M")


def _canon(s):
    return re.sub(r"[^a-z0-9]", "", s.lower())


def _build_column_map(headers):
    canon_headers = {_canon(h): h for h in headers}
    mapping = {}
    for field, syns in SYNONYMS.items():
        for syn in syns:
            if _canon(syn) in canon_headers:
                mapping[field] = canon_headers[_canon(syn)]
                break
    return mapping


def _to_num(v):
    if v is None or str(v).strip() == "":
        return None
    s = re.sub(r"[,$%\s]", "", str(v))
    try:
        return float(s)
    except ValueError:
        return None


def _parse_date(v):
    if not v:
        return None
    s = str(v).strip()
    for fmt in _DATE_FORMATS:
        try:
            return datetime.strptime(s, fmt)
        except ValueError:
            continue
    return None


def _status_score(*values):
    """+1 for a healthy status word, -1 for a failed one, per matched field."""
    score = 0
    for v in values:
        t = (v or "").lower()
        if not t:
            continue
        if any(w in t for w in _NEG_STATUS):
            score -= 1
        elif any(w in t for w in _POS_STATUS):
            score += 1
    return score


def derive(rows, now=None):
    """
    Fill model features from richer raw fields (dates, MOB, status columns)
    without ever fabricating financial amounts. Returns the list of feature
    names we populated so the ingest report can be transparent about it.
    """
    now = now or datetime.now()
    derived = set()
    for r in rows:
        # Last payment date -> days since last payment.
        lpd = _parse_date(r.get("last_payment_date"))
        if lpd and r.get("days_since_last_payment") is None:
            r["days_since_last_payment"] = max(0, (now - lpd).days)
            derived.add("days_since_last_payment")

        # Disbursal + maturity -> term in months; disbursal -> tenure (fallback).
        ld = _parse_date(r.get("lent_date"))
        md = _parse_date(r.get("maturity_date"))
        if ld and md and r.get("term_months") is None:
            r["term_months"] = max(1, round((md - ld).days / 30.0))
            derived.add("term_months")

        # Customer tenure: prefer Months-on-Book, else derive from disbursal date.
        if r.get("customer_tenure_years") is None:
            if r.get("months_on_book") is not None:
                r["customer_tenure_years"] = round(float(r["months_on_book"]) / 12.0, 1)
                derived.add("customer_tenure_years")
            elif ld:
                r["customer_tenure_years"] = round(max(0, (now - ld).days) / 365.0, 1)
                derived.add("customer_tenure_years")

        # Soft payment-behavior proxy from mandate/presentation/reason statuses.
        # Only used when no explicit contact/payment-history features exist; it
        # nudges rpc_rate/pay_ratio priors rather than inventing amounts.
        sc = _status_score(r.get("mandate_status"), r.get("presentation_status"), r.get("bounce_reason"))
        if sc != 0:
            proxy = 0.5 + 0.25 * max(-1, min(1, sc))  # 0.25 (bad) .. 0.75 (good)
            if r.get("pay_ratio_6m") is None:
                r["pay_ratio_6m"] = round(proxy, 3); derived.add("pay_ratio_6m")
            if r.get("rpc_rate") is None:
                r["rpc_rate"] = round(proxy, 3); derived.add("rpc_rate")
    return sorted(derived)


def _read_text(path):
    """
    Read a CSV as text, tolerating non-UTF-8 exports. Excel/Windows LMS files
    are often Windows-1252 or Latin-1. We try UTF-8 (incl. BOM) first, then
    cp1252, then latin-1 (which decodes any byte). Returns (text, encoding).
    """
    with open(path, "rb") as f:
        raw = f.read()
    for enc in ("utf-8-sig", "utf-8", "cp1252", "latin-1"):
        try:
            return raw.decode(enc), enc
        except UnicodeDecodeError:
            continue
    return raw.decode("latin-1", errors="replace"), "latin-1"


def _sniff_delimiter(sample):
    """Pick the most likely delimiter (comma, tab, semicolon, or pipe)."""
    try:
        return csv.Sniffer().sniff(sample, delimiters=",\t;|").delimiter
    except csv.Error:
        counts = {d: sample.count(d) for d in [",", "\t", ";", "|"]}
        return max(counts, key=counts.get) if any(counts.values()) else ","


def _col_letters(ref):
    """'AB12' -> 'AB' (the column part of a spreadsheet cell reference)."""
    m = re.match(r"([A-Za-z]+)", ref or "")
    return m.group(1).upper() if m else ""


def _read_xlsx(path, header_only=False):
    """
    Read the first worksheet of an .xlsx file using only the standard library
    (zipfile + XML). Returns (headers, raw_rows) where raw_rows is a list of
    dicts keyed by header name. With header_only=True it stops after the first
    row (fast header inspection for large books). No openpyxl/pandas dependency.
    """
    import zipfile
    import xml.etree.ElementTree as ET

    ns = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"
    with zipfile.ZipFile(path) as z:
        names = z.namelist()

        shared = []
        if "xl/sharedStrings.xml" in names:
            with z.open("xl/sharedStrings.xml") as f:
                for _, el in ET.iterparse(f):
                    if el.tag == ns + "si":
                        shared.append("".join(t.text or "" for t in el.iter(ns + "t")))
                        el.clear()

        sheet = "xl/worksheets/sheet1.xml"
        if sheet not in names:
            cand = sorted(n for n in names if n.startswith("xl/worksheets/") and n.endswith(".xml"))
            sheet = cand[0] if cand else None
        if not sheet:
            return [], []

        grid = []
        with z.open(sheet) as f:
            for _, el in ET.iterparse(f):
                if el.tag == ns + "row":
                    cells = {}
                    for c in el.findall(ns + "c"):
                        col = _col_letters(c.get("r"))
                        t = c.get("t")
                        v = c.find(ns + "v")
                        if t == "s":
                            val = shared[int(v.text)] if v is not None and v.text else ""
                        elif t == "inlineStr":
                            is_ = c.find(ns + "is")
                            val = "".join(tt.text or "" for tt in is_.iter(ns + "t")) if is_ is not None else ""
                        else:
                            val = v.text if v is not None else ""
                        if col:
                            cells[col] = val
                    grid.append(cells)
                    el.clear()
                    if header_only and len(grid) >= 1:
                        break

    if not grid:
        return [], []
    if header_only:
        header_cells = grid[0]
        ordered = sorted(header_cells.keys(), key=lambda c: (len(c), c))
        return [str(header_cells[c]).strip() for c in ordered], []
    header_cells = grid[0]
    ordered_cols = sorted(header_cells.keys(), key=lambda c: (len(c), c))
    headers = [str(header_cells[c]).strip() for c in ordered_cols]
    col_to_header = {c: str(header_cells[c]).strip() for c in ordered_cols}
    raw_rows = []
    for cells in grid[1:]:
        raw_rows.append({col_to_header.get(c, c): cells.get(c, "") for c in cells})
    return headers, raw_rows


def _looks_like_xlsx(path):
    with open(path, "rb") as f:
        return f.read(4) == b"PK\x03\x04"  # xlsx is a zip archive


def read_headers(path):
    """Return (headers, format) without parsing the whole file. For inspection."""
    if _looks_like_xlsx(path):
        headers, _ = _read_xlsx(path, header_only=True)
        return headers, "xlsx"
    text, _ = _read_text(path)
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    delim = _sniff_delimiter(text[:4096])
    reader = csv.reader(io.StringIO(text, newline=""), delimiter=delim)
    headers = next(reader, [])
    return [h.strip() for h in headers], "csv"


def suggest_mapping(headers):
    """Auto-suggested canonical_field -> source_column map for a header list."""
    return _build_column_map([h.strip() for h in headers])


def _canonicalize(headers, raw_rows, source_kind, mapping=None):
    """
    Map arbitrary headers -> canonical fields, coerce, derive, build report.
    If `mapping` (canonical_field -> source_column) is given, it is used
    instead of auto-detection; blank/unknown source columns are ignored.
    """
    headers = [h.strip() for h in headers]
    if mapping:
        header_set = set(headers)
        colmap = {field: src for field, src in mapping.items()
                  if src and src.strip() in header_set}
    else:
        colmap = _build_column_map(headers)
    rows = []
    for i, raw in enumerate(raw_rows):
        row = {}
        for field, src in colmap.items():
            val = raw.get(src)
            row[field] = _to_num(val) if field in NUMERIC else ("" if val is None else str(val).strip())
        if not row.get("account_id"):
            row["account_id"] = f"ROW-{i:05d}"
        rows.append(row)

    derived_fields = derive(rows)
    report = {
        "source_columns": headers,
        "mapped": colmap,
        "format": source_kind,
        "mapping_mode": "manual" if mapping else "auto",
        "recognized_fields": sorted(colmap.keys()),
        "derived_fields": derived_fields,
        "unmapped_source_columns": [h for h in headers if h not in colmap.values()],
        "missing_canonical_fields": sorted(set(SYNONYMS) - set(colmap)),
        "row_count": len(rows),
    }
    return rows, report


def load_csv(path, mapping=None):
    """
    Load a CSV or XLSX account file. Returns (rows, report). If `mapping`
    (canonical_field -> source_column) is provided, it overrides auto-detection.
    (Name kept for backwards compatibility; it also handles Excel .xlsx.)
    """
    if _looks_like_xlsx(path):
        headers, raw_rows = _read_xlsx(path)
        rows, report = _canonicalize(headers, raw_rows, "xlsx", mapping=mapping)
        report["encoding"] = "xlsx (Excel)"
        report["delimiter"] = "n/a"
        return rows, report

    text, encoding = _read_text(path)
    # Normalize CRLF / old-Mac CR line endings so csv sees clean '\n' terminators
    # (Windows/Excel exports otherwise raise "new-line character in unquoted field").
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    delimiter = _sniff_delimiter(text[:4096])
    reader = csv.DictReader(io.StringIO(text, newline=""), delimiter=delimiter)
    headers = [h.strip() for h in (reader.fieldnames or [])]
    reader.fieldnames = headers
    raw_rows = list(reader)
    rows, report = _canonicalize(headers, raw_rows, "csv", mapping=mapping)
    report["encoding"] = encoding
    report["delimiter"] = {",": "comma", "\t": "tab", ";": "semicolon", "|": "pipe"}.get(delimiter, delimiter)
    return rows, report
