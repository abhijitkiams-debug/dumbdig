"""
Borrower payment-INTENT assessment (willingness to pay).

This is deliberately distinct from the two other numbers Aayudh produces:
  - priority   -> risk / urgency: who to work first;
  - pay-prob   -> statistical probability the account pays;
  - INTENT     -> does THIS borrower actually mean to pay, banded for collectors:
                  Very High > High > Medium > Low > May not pay.

Intent is grounded in willingness evidence, not an inversion of risk. The
payment probability is the backbone; explicit intent signals move it up or down,
with hard floors for stated unwillingness:
  +  active promise-to-pay, recent part-payment / high POS-paid %, cooperative
     call dispositions ("will pay", "PTP"), digital engagement (opens messages),
     easy-cure profile.
  -  refusal / dispute / absconding / account-closed (unwillingness), rolling
     forward month-on-month, "refused" / "not reachable" dispositions, long
     silence since last payment.
Every band carries the human-readable reasons that produced it (auditability).
"""

# Ordered weakest -> strongest so a band can be capped by a hard floor.
BANDS = ["May not pay", "Low", "Medium", "High", "Very High"]

_COOPERATIVE = ("will pay", "willing", "ptp", "promise", "part pay", "part-pay",
                "callback", "call back", "agreed", "ready to pay", "assured")
_UNCOOPERATIVE = ("refuse", "refused", "denied", "not paying", "will not pay",
                  "wont pay", "won't pay", "dispute", "abscond", "not reachable",
                  "switched off", "wrong number", "untraceable", "no response")


def _num(v):
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


def assess(account, sig, kb, pay_prob, strat=None):
    """Return {intent_score, intent_band, intent_reasons} for one account."""
    comp = sig.get("components", {})
    reason_key = kb.reason_key(account.get("bounce_reason"), account.get("field_feedback"))
    score = float(pay_prob)
    reasons = []

    # ---- explicit (un)willingness: hard floor on the band ------------------
    hard_floor = None
    if reason_key == "refusal":
        hard_floor = "May not pay"; reasons.append("borrower refused to pay")
    elif reason_key == "absconding":
        hard_floor = "May not pay"; reasons.append("borrower absconding / untraceable")
    elif reason_key == "account_closed":
        hard_floor = "Low"; reasons.append("bank account closed")
    elif reason_key == "dispute":
        hard_floor = "Low"; reasons.append("borrower disputing the debt")

    # ---- positive intent signals -------------------------------------------
    ptp = str(account.get("has_active_ptp") or "").strip() in ("1", "True", "true") \
        or bool((strat or {}).get("ptp_active"))
    if ptp:
        score += 0.18; reasons.append("active promise-to-pay on record")

    pp = _num(account.get("pos_paid_pct"))
    if pp is not None:
        pp = pp / 100.0 if pp > 1 else pp
        if pp >= 0.30:
            score += 0.10; reasons.append("paying down principal")
        elif pp <= 0.05:
            score -= 0.05
    pr = _num(account.get("pay_ratio_6m"))
    if pr is not None and pr >= 0.6:
        score += 0.06; reasons.append("consistent recent repayment")

    fb = (str(account.get("field_feedback") or "") + " " +
          str(account.get("bounce_reason") or "")).lower()
    if any(w in fb for w in _COOPERATIVE):
        score += 0.10; reasons.append("cooperative in contact")
    if any(w in fb for w in _UNCOOPERATIVE):
        score -= 0.15

    reads = _num(account.get("wa_read_count"))
    if reads is not None and reads > 0:
        score += 0.04; reasons.append("opens our messages")

    ec = str(account.get("easy_cure_flag") or "").strip().lower()
    if ec in ("1", "y", "yes", "true", "easy", "easycure"):
        score += 0.06

    # ---- negative intent signals -------------------------------------------
    rf = _num(account.get("roll_forward"))
    if rf is not None and rf >= 1.0:
        score -= 0.10; reasons.append("rolling forward (worsening)")
    rec = comp.get("recency")
    if rec is not None and rec >= 0.8:
        score -= 0.06; reasons.append("long silence since last payment")

    score = max(0.0, min(1.0, score))

    band = ("Very High" if score >= 0.78 else
            "High" if score >= 0.58 else
            "Medium" if score >= 0.40 else
            "Low" if score >= 0.22 else
            "May not pay")

    if hard_floor is not None and BANDS.index(band) > BANDS.index(hard_floor):
        band = hard_floor

    if not reasons:
        reasons = ["based on payment likelihood"]
    return {"intent_score": round(score, 4), "intent_band": band, "intent_reasons": reasons[:3]}
