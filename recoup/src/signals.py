"""
Flexible, signal-based collections scoring.

Data from different lenders is wildly inconsistent, so priority is not a fixed
formula over a fixed schema. Instead we define a catalog of risk SIGNALS; each
account's score is a weighted blend of only the signals that are (a) present and
(b) actually informative in *this* upload.

Key mechanisms (these fix the "everything High/Low" problem on thin files):
  - coverage gating      : a signal absent from the file contributes nothing.
  - informativeness gating: a signal that is ~constant across the portfolio
                            (e.g. "everyone bounced for insufficient funds")
                            is down-weighted to near-zero automatically.
  - dynamic re-weighting  : weights renormalize over the surviving signals.
  - relative + absolute   : magnitude signals are percentile-ranked within the
                            book (guaranteed spread); objective red flags
                            (DPD>=90, account closed, refused) override upward.
  - confidence            : how many signal families actually drove the score.
Knowledge-base weights (learned from feedback/outcomes) multiply the base
weights, so the model keeps improving.
"""

import numpy as np


def _clip(x, lo=0.0, hi=1.0):
    return max(lo, min(hi, x))


def _num(v):
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


# Each signal: name, group, base weight, mode ('abs' already 0..1, or 'rel'
# magnitude to be percentile-ranked), an extractor, and an optional red-flag.
def _delinquency(a, kb):
    dpd = _num(a.get("days_past_due"))
    if dpd is not None:
        return _clip(dpd / 180.0)
    b = _num(a.get("current_bucket"))
    return _clip(b / 4.0) if b is not None else None


def _exposure(a, kb):
    v = _num(a.get("total_balance")) or _num(a.get("past_due_amount"))
    return v if v and v > 0 else None


def _failure(a, kb):
    pres, mand = a.get("presentation_status"), a.get("mandate_status")
    f = None
    if pres:
        f = 0.7 if kb.status_is_negative(pres) else 0.1
    if mand and kb.status_is_negative(mand):
        f = max(f or 0.0, 0.8)
    return f


def _reason(a, kb):
    return kb.reason_severity(a.get("bounce_reason"), a.get("field_feedback"))


def _recency(a, kb):
    d = _num(a.get("days_since_last_payment"))
    return d if d is not None else None


def _behaviour(a, kb):
    pr = _num(a.get("pay_ratio_6m"))
    if pr is not None:
        return _clip(1.0 - pr)
    n = _num(a.get("num_payments_12m"))
    return _clip(1.0 - n / 12.0) if n is not None else None


def _vintage(a, kb):
    mob = _num(a.get("months_on_book"))
    if mob is None and a.get("customer_tenure_years") is not None:
        mob = _num(a.get("customer_tenure_years")) * 12.0
    if mob is None:
        return None
    # Early-EMI defaults are the biggest red flag; chronic long vintage also elevated.
    if mob <= 4:
        return 0.90
    if mob <= 8:
        return 0.65
    if mob <= 18:
        return 0.40
    if mob >= 48:
        return 0.55
    return 0.30


def _stage(a, kb):
    return kb.stage_severity(a.get("paid_by"))


def _trajectory(a, kb):
    # Roll-forward momentum: 1.0 = deteriorating ("Stab forward"), 0.0 = held/paid.
    rf = _num(a.get("roll_forward"))
    return rf if rf is not None else None


def _contact(a, kb):
    parts = []
    ref = _num(a.get("refusals_6m"))
    if ref:
        parts.append(_clip(0.2 * ref))
    rpc = _num(a.get("rpc_rate"))
    if rpc is not None:
        parts.append(_clip(1.0 - rpc))
    return max(parts) if parts else None


SIGNALS = [
    {"name": "delinquency", "group": "Delinquency", "w": 0.19, "mode": "abs", "fn": _delinquency},
    {"name": "exposure",    "group": "Exposure",    "w": 0.15, "mode": "rel", "fn": _exposure},
    {"name": "trajectory",  "group": "Roll trajectory", "w": 0.12, "mode": "abs", "fn": _trajectory},
    {"name": "recency",     "group": "Payment recency", "w": 0.11, "mode": "rel", "fn": _recency},
    {"name": "reason",      "group": "Reason severity", "w": 0.11, "mode": "abs", "fn": _reason},
    {"name": "failure",     "group": "Payment failure", "w": 0.09, "mode": "abs", "fn": _failure},
    {"name": "stage",       "group": "Collection stage", "w": 0.09, "mode": "abs", "fn": _stage},
    {"name": "behaviour",   "group": "Repayment behaviour", "w": 0.07, "mode": "abs", "fn": _behaviour},
    {"name": "vintage",     "group": "Vintage",     "w": 0.04, "mode": "abs", "fn": _vintage},
    {"name": "contact",     "group": "Contactability", "w": 0.03, "mode": "abs", "fn": _contact},
]

REASON_TEXT = {
    "delinquency": lambda a: (f"{int(_num(a.get('days_past_due')))} days past due"
                              if a.get("days_past_due") not in (None, "") else
                              (f"delinquency bucket {int(_num(a.get('current_bucket')))}" if a.get("current_bucket") not in (None, "") else "delinquent")),
    "exposure": lambda a: "high outstanding exposure (POS)",
    "recency": lambda a: (f"{int(_num(a.get('days_since_last_payment')))} days since last payment"
                          if a.get("days_since_last_payment") not in (None, "") else "long time since last payment"),
    "failure": lambda a: "auto-debit / presentation failed",
    "behaviour": lambda a: "weak recent repayment history",
    "vintage": lambda a: "early-stage / vintage risk",
    "stage": lambda a: (f"already at {a.get('paid_by')} stage" if a.get("paid_by") else "later collection stage"),
    "contact": lambda a: "hard to contact / refusals",
    "trajectory": lambda a: "rolling forward (missed last month, worsening)",
}


def _reason_text(name, a, kb):
    if name == "reason":
        key = kb.reason_key(a.get("bounce_reason"), a.get("field_feedback"))
        raw = (a.get("bounce_reason") or a.get("field_feedback") or "").strip()
        labels = {
            "refusal": "borrower refused / declined to pay", "absconding": "borrower absconding / untraceable",
            "account_closed": "bank account closed", "stop_payment": "payment stopped by customer",
            "dispute": "borrower disputing the debt", "deceased": "borrower deceased — special handling",
            "mandate_invalid": "invalid / inactive auto-debit mandate", "insufficient_funds": "bounced — insufficient funds",
            "technical": "technical / operational bounce",
        }
        return labels.get(key, f"adverse reason: {raw}" if raw else "adverse payment reason")
    fn = REASON_TEXT.get(name)
    return fn(a) if fn else name


def _redflag(a, kb):
    """Objective conditions that force at least High regardless of the book."""
    dpd = _num(a.get("days_past_due"))
    if dpd is not None and dpd >= 90:
        return True
    b = _num(a.get("current_bucket"))
    if b is not None and b >= 3:
        return True
    key = kb.reason_key(a.get("bounce_reason"), a.get("field_feedback"))
    if key in ("refusal", "absconding", "account_closed", "dispute"):
        return True
    return False


def score_portfolio(accounts, kb):
    """
    accounts: list of canonicalized dicts. kb: KnowledgeBase.
    Returns (results, meta) where results is a per-account list of dicts and
    meta describes which signals were used/gated (portfolio transparency).
    """
    n = len(accounts)
    if n == 0:
        return [], {"signals": []}

    raw = {}      # signal -> np.array of raw values (nan where missing)
    norm = {}     # signal -> np.array normalized 0..1 (nan where missing)
    meta_sig = []
    for sig in SIGNALS:
        vals = np.array([sig["fn"](a, kb) if sig["fn"](a, kb) is not None else np.nan
                         for a in accounts], dtype=float)
        raw[sig["name"]] = vals
        present = ~np.isnan(vals)
        coverage = float(present.mean())
        v = vals.copy()
        if sig["mode"] == "rel" and present.sum() > 1:
            # percentile-rank present values into 0..1
            order = np.argsort(np.argsort(vals[present]))
            pr = order / max(1, present.sum() - 1)
            v[present] = pr
        v = np.clip(v, 0, 1)
        norm[sig["name"]] = v
        std = float(np.nanstd(v)) if present.any() else 0.0
        # informativeness: constant signals shrink toward zero weight.
        info = _clip(std / 0.15, 0.0, 1.0)
        gated = coverage < 0.15 or info < 0.12
        eff_w = 0.0 if gated else sig["w"] * kb.weight(sig["name"]) * coverage * max(info, 0.2)
        meta_sig.append({
            "name": sig["name"], "group": sig["group"], "coverage": round(coverage, 2),
            "variation": round(std, 3), "gated": gated, "effective_weight": round(eff_w, 4),
        })

    active = [m for m in meta_sig if not m["gated"] and m["effective_weight"] > 0]
    wsum = sum(m["effective_weight"] for m in active) or 1.0

    # composite per account (weighted avg over active signals; missing values
    # treated as the signal's own mean so absence doesn't bias up or down).
    composite = np.zeros(n)
    for m in active:
        v = norm[m["name"]].copy()
        col_mean = np.nanmean(v) if np.isfinite(np.nanmean(v)) else 0.0
        v = np.where(np.isnan(v), col_mean, v)
        composite += (m["effective_weight"] / wsum) * v

    # percentile of composite for relative spread
    if n > 1:
        pct = np.argsort(np.argsort(composite)) / (n - 1)
    else:
        pct = np.array([0.5])

    n_groups = len({m["group"] for m in active})
    confidence = "high" if n_groups >= 5 else ("medium" if n_groups >= 3 else "low")

    results = []
    # Level cutoffs are percentile-anchored so we always get a usable spread
    # (who to work first), with objective red flags overriding to High and a
    # low absolute floor keeping genuinely benign accounts Low. This avoids a
    # uniformly-severe book collapsing entirely into "High".
    for i, a in enumerate(accounts):
        red = _redflag(a, kb)
        comp = float(composite[i])
        p = float(pct[i])
        if red or p >= 0.75 or comp >= 0.80:
            level = "High"
        elif p >= 0.40 or comp >= 0.55:
            level = "Medium"
        else:
            level = "Low"
        # absolute floor: trivially low composite is Low regardless of rank
        if comp < 0.20 and not red:
            level = "Low"
        # easy-cure accounts tend to self-cure with a light touch; don't burn a
        # High slot on them unless an objective red flag says otherwise.
        ec = str(a.get("easy_cure_flag") or "").strip().lower()
        if not red and ec in ("1", "y", "yes", "true", "easy", "easycure") and level == "High":
            level = "Medium"
        score = round(100 * comp, 1)

        # reasons: strongest contributing active signals for this account
        contribs = []
        for m in active:
            val = norm[m["name"]][i]
            if not np.isnan(val):
                contribs.append((m["effective_weight"] / wsum * val, m["name"]))
        contribs.sort(reverse=True)
        reasons = []
        for _, name in contribs[:3]:
            txt = _reason_text(name, a, kb)
            if txt and txt not in reasons:
                reasons.append(txt)
        if red and not any("refus" in r or "abscond" in r or "closed" in r or "disput" in r for r in reasons):
            rt = _reason_text("reason", a, kb)
            if rt:
                reasons.insert(0, rt)
        if not reasons:
            reasons = ["limited signal; light-touch reminder"]

        results.append({
            "priority_score": score,
            "priority_level": level,
            "reasons": reasons[:4],
            "confidence": confidence,
            "composite": round(comp, 4),
            "percentile": round(p, 3),
            "components": {m["name"]: round(float(norm[m["name"]][i]), 3)
                           for m in active if not np.isnan(norm[m["name"]][i])},
            "redflag": red,
        })

    meta = {"signals": meta_sig, "active_groups": n_groups, "confidence": confidence}
    return results, meta
