"""
Flexible ingestion so real uploaded data works, not just our synthetic file.

Collections exports never share a schema. This module maps arbitrary column
names onto Recoup's canonical fields using a synonym dictionary, coerces types,
and fills sensible defaults for anything missing -- reporting exactly what it
found so nothing is silently fabricated.
"""

import csv
import re

# canonical_field -> list of accepted source-column synonyms (lowercased, we
# also strip non-alphanumerics before matching so "Past Due Amount" == "past_due_amount").
SYNONYMS = {
    "account_id": ["account_id", "loan_id", "id", "accountnumber", "loanid", "reference"],
    "region": ["region", "area", "zone", "branch", "territory"],
    "geo_x": ["geo_x", "x", "longitude", "lon", "lng"],
    "geo_y": ["geo_y", "y", "latitude", "lat"],
    "age": ["age"],
    "occupation": ["occupation", "job", "employment"],
    "marital_status": ["marital_status", "marital", "maritalstatus"],
    "customer_tenure_years": ["customer_tenure_years", "tenure", "customertenure"],
    "num_co_borrowers": ["num_co_borrowers", "coborrowers", "cosigners", "guarantors"],
    "lent_amount": ["lent_amount", "loanamount", "principal", "disbursedamount"],
    "interest": ["interest", "interestrate", "rate", "apr"],
    "term_months": ["term_months", "term", "tenor", "loanterm"],
    "total_balance": ["total_balance", "balance", "outstanding", "totaloutstanding"],
    "next_installment_amount": ["next_installment_amount", "nextinstallment", "emi", "installment", "nextduamount"],
    "past_due_amount": ["past_due_amount", "pastdue", "overdue", "arrears", "amountoverdue"],
    "current_bucket": ["current_bucket", "bucket", "delinquencybucket", "dpdbucket"],
    "days_past_due": ["days_past_due", "dpd", "dayspastdue", "dayoverdue"],
    "avg_pay_amount_ever": ["avg_pay_amount_ever", "avgpayment", "averagepayment"],
    "last_pay_amount": ["last_pay_amount", "lastpayment", "lastpaidamount"],
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
    "geo_x", "geo_y", "age", "customer_tenure_years", "num_co_borrowers",
    "lent_amount", "interest", "term_months", "total_balance",
    "next_installment_amount", "past_due_amount", "current_bucket", "days_past_due",
    "avg_pay_amount_ever", "last_pay_amount", "days_since_last_payment",
    "num_payments_12m", "pay_ratio_6m", "std_pay_amount", "contact_attempts_6m",
    "rpc_rate", "promises_made_6m", "promises_kept_6m", "refusals_6m",
    "days_since_last_contact", "best_contact_hour",
    "label_promise_to_pay", "label_actual_payment",
}


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


def load_csv(path):
    """
    Returns (rows, report) where rows is a list of canonicalized dicts and
    report describes the mapping so we can show the user what was recognized.
    """
    with open(path, newline="") as f:
        reader = csv.DictReader(f)
        headers = reader.fieldnames or []
        colmap = _build_column_map(headers)
        rows = []
        for i, raw in enumerate(reader):
            row = {}
            for field, src in colmap.items():
                val = raw.get(src)
                row[field] = _to_num(val) if field in NUMERIC else (val or "").strip()
            if "account_id" not in row or not row.get("account_id"):
                row["account_id"] = f"ROW-{i:05d}"
            rows.append(row)

    report = {
        "source_columns": headers,
        "mapped": colmap,
        "recognized_fields": sorted(colmap.keys()),
        "unmapped_source_columns": [h for h in headers if h not in colmap.values()],
        "missing_canonical_fields": sorted(set(SYNONYMS) - set(colmap)),
        "row_count": len(rows),
    }
    return rows, report
