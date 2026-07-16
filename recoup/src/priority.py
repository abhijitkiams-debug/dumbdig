"""
Collections risk-priority scoring (domain-driven, interpretable).

The ML models estimate *likelihood to pay*. But operational priority in a
recovery book is driven by how much is at stake and how much intervention an
account needs -- not by pay-likelihood alone. A high-DPD, high-POS, bounced or
refused, early-vintage account is HIGH priority to work (escalate), even when
the probability of a voluntary payment is low.

This module blends four transparent components, using whatever fields are
present, and returns a 0-100 score, a level, and plain-English reasons that name
the actual drivers (bounce reason, refusal, vintage, etc.).
"""

# --- reason / status vocabularies -----------------------------------------
# Willful or hard negative signals (borrower unwilling / unable, account gone).
HARD_NEG = (
    "refus", "declin", "decline", "stop payment", "stoppayment", "payment stopped",
    "account closed", "acc closed", "closed account", "no funds", "insufficient",
    "insuff", "exceed arrangement", "not interested", "unwilling", "willful",
    "wilful", "abscond", "skip", "dispute", "denied", "rtp", "withdrawal stopped",
    "funds insufficient", "cheque return", "bounce", "return", "dishonour", "dishonor",
    "npa", "settlement", "legal", "fraud", "death", "expired",
)
# Operational / technical bounces (fixable, less about willingness).
TECH_NEG = (
    "signature", "mismatch", "technical", "mandate not", "not registered", "image",
    "cts", "micr", "inward", "connectivity", "amount mismatch", "other reason",
    "instrument", "date", "stale",
)
POS_SIGNAL = (
    "paid", "cleared", "clear", "success", "presented-paid", "collected", "active",
    "honour", "honor", "promise", "ptp", "will pay", "part paid", "partpaid",
    "regular", "resolved",
)


def _clip(x, lo=0.0, hi=1.0):
    return max(lo, min(hi, x))


def _reason_label(*texts):
    """A specific, accurate phrase for the adverse reason (not just 'refused')."""
    s = " ".join((t or "") for t in texts).lower().strip()
    raw = next((t.strip() for t in texts if t and t.strip()), "")
    if any(w in s for w in ("refus", "declin", "not interested", "unwilling", "willful", "wilful", "denied")):
        return "borrower refused / declined to pay" + (f": {raw}" if raw else "")
    if any(w in s for w in ("insuffic", "no funds", "funds insufficient")):
        return "cheque bounced — insufficient funds"
    if any(w in s for w in ("account closed", "acc closed", "closed account")):
        return "bank account closed"
    if any(w in s for w in ("stop payment", "stoppayment", "payment stopped", "withdrawal stopped")):
        return "payment stopped by customer"
    if any(w in s for w in ("abscond", "skip", "untraceable")):
        return "borrower absconding / untraceable"
    if any(w in s for w in ("dispute", "not liable")):
        return "borrower disputing the debt"
    if any(w in s for w in ("death", "expired", "deceased")):
        return "borrower deceased — needs special handling"
    if any(w in s for w in ("legal", "npa", "settlement", "fraud")):
        return f"adverse status: {raw or s}"
    return f"adverse reason: {raw}" if raw else "adverse payment reason"


def _reason_severity(*texts):
    """0 (none/positive) .. 1 (hard willful negative) from free-text reasons."""
    sev = 0.0
    for t in texts:
        s = (t or "").lower()
        if not s:
            continue
        if any(w in s for w in HARD_NEG):
            sev = max(sev, 1.0)
        elif any(w in s for w in TECH_NEG):
            sev = max(sev, 0.35)
        elif any(w in s for w in POS_SIGNAL):
            sev = max(sev, 0.0)
    return sev


def assess(acc, exposure_n):
    """
    acc: canonicalized account dict (fields may be missing).
    exposure_n: POS/exposure normalized to [0,1] across the portfolio.
    Returns dict: score, level, reasons, components, escalate, hard_risk.
    """
    dpd = acc.get("days_past_due")
    bucket = acc.get("current_bucket")
    pres = (acc.get("presentation_status") or "")
    mand = (acc.get("mandate_status") or "")
    reason = (acc.get("bounce_reason") or "")
    feedback = (acc.get("field_feedback") or "")
    refusals = acc.get("refusals_6m") or 0
    pay_ratio = acc.get("pay_ratio_6m")
    mob = acc.get("months_on_book")
    if mob is None and acc.get("customer_tenure_years") is not None:
        mob = float(acc["customer_tenure_years"]) * 12.0

    # 1) Delinquency severity ------------------------------------------------
    if dpd is not None:
        severity = _clip(float(dpd) / 180.0)
    elif bucket is not None:
        severity = _clip(float(bucket) / 4.0)
    else:
        severity = 0.35
    delinquent = severity > 0.15 or (dpd and dpd > 0) or (bucket and bucket > 0)

    # 2) Exposure (value at risk) -------------------------------------------
    exposure = _clip(exposure_n)

    # 3) Payment / behavioral risk ------------------------------------------
    risk = 0.20
    pl = pres.lower()
    ml = mand.lower()
    if any(w in pl for w in ("bounce", "return", "unpaid", "dishonour", "dishonor", "fail")):
        risk += 0.22
    if any(w in ml for w in ("cancel", "inactive", "reject", "expire", "closed")):
        risk += 0.20
    rs = _reason_severity(reason, feedback)
    risk += 0.30 * rs
    if refusals:
        risk += min(0.20, 0.10 * float(refusals))
    if pay_ratio is not None and float(pay_ratio) < 0.4:
        risk += 0.10
    risk = _clip(risk)

    # 4) Vintage concern -----------------------------------------------------
    # Early-stage default (first EMIs bouncing) is a serious red flag; long
    # vintage still delinquent is a chronic concern. (Vintage analysis.)
    vintage = 0.30
    early_default = False
    if mob is not None:
        if mob <= 6 and delinquent:
            vintage, early_default = 0.90, True
        elif mob <= 12 and delinquent:
            vintage = 0.60
        elif mob >= 36 and delinquent:
            vintage = 0.55
        else:
            vintage = 0.25

    components = {
        "severity": round(severity, 3),
        "exposure": round(exposure, 3),
        "risk": round(risk, 3),
        "vintage": round(vintage, 3),
    }
    score = 100.0 * (0.30 * severity + 0.25 * exposure + 0.30 * risk + 0.15 * vintage)
    score = round(_clip(score, 0, 100), 1)
    level = "High" if score >= 55 else ("Medium" if score >= 33 else "Low")

    hard_risk = rs >= 0.9 or refusals >= 2

    # ---- reasons: name the strongest actual drivers -----------------------
    reasons = []
    if severity >= 0.5:
        if dpd is not None:
            reasons.append((severity, f"{int(dpd)} days past due"))
        elif bucket is not None:
            reasons.append((severity, f"in delinquency bucket {int(bucket)}"))
    if exposure >= 0.6:
        reasons.append((exposure, "high outstanding exposure (POS)"))
    if rs >= 0.9:
        reasons.append((0.95, _reason_label(reason, feedback)))
    elif "bounce" in pl or "return" in pl or "unpaid" in pl:
        reasons.append((0.7, "payment bounced" + (f" ({reason.strip()})" if reason.strip() else "")))
    if any(w in ml for w in ("cancel", "inactive", "reject", "expire", "closed")):
        reasons.append((0.75, f"auto-debit mandate {mand.strip().lower()}"))
    if feedback.strip() and _reason_severity(feedback) >= 0.9:
        reasons.append((0.72, f"field feedback: {feedback.strip()}"))
    if refusals:
        reasons.append((0.6, f"{int(refusals)} refusal(s) recorded"))
    if early_default:
        reasons.append((0.9, f"early-stage default (month {int(mob)} on book)"))
    elif mob is not None and mob >= 36 and delinquent:
        reasons.append((0.5, "long-vintage account still delinquent"))

    reasons = [t for _, t in sorted(reasons, key=lambda r: -r[0])][:4]
    if not reasons:
        reasons = ["low delinquency and exposure; monitor with light-touch reminders"]

    escalate = level == "High" and (hard_risk or exposure >= 0.6)
    return {
        "score": score, "level": level, "reasons": reasons,
        "components": components, "escalate": escalate, "hard_risk": hard_risk,
        "early_default": early_default,
    }
