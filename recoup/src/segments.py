"""
Lead aggregation into actionable SEGMENTS.

A collections team does not action 100k rows one by one — it works *segments*.
This module combines the three orthogonal signals Aayudh already produces —
priority (risk/urgency), intent (willingness to pay) and payment probability —
into a small set of mutually-exclusive, named, ready-to-run segments. Each
segment carries a recommended channel and a workflow-orchestration payload so it
can be pushed straight into a campaign / voice-blast / workflow platform.

Segments are ordered by actionability; every account lands in the FIRST segment
it matches, so segment lead-counts always sum back to the scored portfolio.
"""

# Payment-probability bands.
def _pp_band(p):
    p = float(p or 0)
    return "high" if p >= 0.60 else "mid" if p >= 0.35 else "low"


_STRONG_INTENT = {"Very High", "High"}
_WEAK_INTENT = {"Low", "May not pay"}


def _classify(w):
    """Return the segment id an account belongs to (first match wins)."""
    pri = w.get("priority_level")
    intent = w.get("intent_band")
    pp = _pp_band(w.get("prob_actual_payment"))
    ptp = bool(w.get("ptp_active"))
    review = bool(w.get("review"))

    # 1. A live promise-to-pay is the most time-sensitive, highest-yield action.
    if ptp:
        return "ptp_followup"
    # 2. High risk + willing + can pay -> put a person / voice on it now.
    if pri == "High" and intent in _STRONG_INTENT and pp in ("high", "mid"):
        return "hot_recovery"
    # 3. Willing + cheap to win (not top risk) -> digital / voice-blast quick wins.
    if intent in _STRONG_INTENT and pri in ("Low", "Medium") and pp in ("high", "mid"):
        return "quick_wins"
    # 4. High risk but unwilling / unreachable -> field & legal escalation.
    if pri == "High" and (intent in _WEAK_INTENT or review):
        return "escalate_field"
    # 5. On the fence -> negotiate (agent call / settlement).
    if intent == "Medium" or (pri in ("High", "Medium") and pp == "mid"):
        return "persuadable"
    # 6. Everything else -> low-cost nurture / monitor.
    return "monitor"


# id -> presentation + recommended channel. Order defines display order.
SEGMENT_DEFS = [
    {"id": "ptp_followup",  "name": "Promise-to-Pay follow-up",
     "subtitle": "Live promise on record; confirm around the promised date",
     "type": "Voice + WhatsApp", "channel": "whatsapp",
     "basis": ["Active PTP", "Any priority"]},
    {"id": "hot_recovery",  "name": "Hot recovery",
     "subtitle": "High priority, willing to pay, good payment odds",
     "type": "Voice bot + Agent", "channel": "agent_call",
     "basis": ["High priority", "Strong intent", "Pay-prob ≥ mid"]},
    {"id": "quick_wins",    "name": "Quick digital wins",
     "subtitle": "Willing payers, lower risk, cheap to convert",
     "type": "Voice blast + SMS", "channel": "sms",
     "basis": ["Low/Med priority", "Strong intent", "Pay-prob ≥ mid"]},
    {"id": "persuadable",   "name": "Persuadable",
     "subtitle": "On the fence; needs a conversation or an offer",
     "type": "Agent + Settlement", "channel": "agent_call",
     "basis": ["Medium intent", "Mid pay-prob"]},
    {"id": "escalate_field","name": "Field & legal escalation",
     "subtitle": "High risk, unwilling or unreachable; human-led recovery",
     "type": "Field + Legal", "channel": "field_visit",
     "basis": ["High priority", "Weak intent / flagged"]},
    {"id": "monitor",       "name": "Low-touch nurture",
     "subtitle": "Low value or low intent; keep warm at minimal cost",
     "type": "IVR + SMS", "channel": "ivr",
     "basis": ["Low priority", "Low intent / pay-prob"]},
]


def build_segments(worklist):
    """Aggregate a scored worklist into ordered segment cards with payloads."""
    buckets = {d["id"]: [] for d in SEGMENT_DEFS}
    for w in worklist:
        seg_id = _classify(w)
        w["lead_segment"] = seg_id       # tag each lead so the UI can filter to it
        buckets.setdefault(seg_id, []).append(w)

    out = []
    for d in SEGMENT_DEFS:
        rows = buckets.get(d["id"], [])
        if not rows:
            continue
        outstanding = sum(float(w.get("recoverable_amount") or 0) for w in rows)
        expected = sum(float(w.get("expected_payment") or 0) for w in rows)
        # intent / priority mix, for the card's at-a-glance composition
        pri_mix = {"High": 0, "Medium": 0, "Low": 0}
        for w in rows:
            pri_mix[w.get("priority_level", "Low")] = pri_mix.get(w.get("priority_level", "Low"), 0) + 1
        out.append({
            **{k: d[k] for k in ("id", "name", "subtitle", "type", "channel", "basis")},
            "leads": len(rows),
            "outstanding": round(outstanding, 2),
            "expected_recovery": round(expected, 2),
            "priority_mix": pri_mix,
        })
    return out


def workflow_payload(segment_def, rows, lender="default"):
    """
    Build a workflow-orchestration payload for a segment: one record per lead,
    carrying exactly what a campaign / voice-blast / workflow node needs to act.
    """
    leads = []
    for w in rows:
        strat = w.get("strategy") or {}
        leads.append({
            "account_id": w.get("account_id"),
            "name": w.get("borrower_name") or "",
            "mobile": w.get("mobile") or "",
            "language_region": w.get("region") or "",
            "priority": w.get("priority_level"),
            "intent_to_pay": w.get("intent_band"),
            "payment_probability": w.get("prob_actual_payment"),
            "outstanding": w.get("recoverable_amount"),
            "recommended_channel": strat.get("first_action") or segment_def.get("channel"),
            "first_message": (strat.get("touches") or [{}])[0].get("message", ""),
            "next_best_action": strat.get("label"),
        })
    return {
        "segment_id": segment_def["id"],
        "segment_name": segment_def["name"],
        "lender": lender,
        "recommended_channel": segment_def["channel"],
        "lead_count": len(leads),
        "leads": leads,
    }
