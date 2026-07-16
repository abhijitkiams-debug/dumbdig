"""
Collection strategy engine: multi-channel, sequenced, with content.

A single "next best action" is not how collections works. This module turns an
account's signals into a *treatment path* -- an ordered cadence of touches, each
with a channel (WhatsApp / SMS / IVR / AI voicebot / email / agent call / field
visit / letter / settlement / legal), a day offset, a script type, a tone, and
an actual message template the agent can use. It also picks an archetype
(digital-first, tele-hybrid, field, settlement/restructure, legal/hard, hybrid)
based on willingness-vs-capacity, reachability, exposure (POS), delinquency
stage, vintage and mandate state -- balancing recovery against channel cost.
"""

# Channel economics: relative cost + intensity (softness->hardness).
CHANNELS = {
    "whatsapp":    {"label": "WhatsApp",       "cost": 2,  "intensity": 1, "digital": True},
    "sms":         {"label": "SMS",            "cost": 1,  "intensity": 1, "digital": True},
    "email":       {"label": "Email",          "cost": 1,  "intensity": 1, "digital": True},
    "ivr":         {"label": "IVR call",       "cost": 2,  "intensity": 2, "digital": True},
    "voicebot":    {"label": "AI voicebot",    "cost": 3,  "intensity": 2, "digital": True},
    "agent_call":  {"label": "Agent call",     "cost": 8,  "intensity": 3, "digital": False},
    "letter":      {"label": "Letter / notice","cost": 6,  "intensity": 3, "digital": False},
    "field_visit": {"label": "Field visit",    "cost": 25, "intensity": 4, "digital": False},
    "settlement":  {"label": "Settlement offer","cost": 10,"intensity": 3, "digital": False},
    "legal":       {"label": "Legal action",   "cost": 40, "intensity": 5, "digital": False},
}

TONE_LADDER = ["empathetic", "neutral", "firm", "final-warning", "legal"]


def _msg(script_type, ctx):
    """Return an example, India-appropriate message/talking-point for a script."""
    pos = ctx.get("pos_fmt", "the outstanding amount")
    emi = ctx.get("emi_fmt", "your EMI")
    lender = ctx.get("lender", "your lender")
    templates = {
        "gentle_reminder": f"Hi, a gentle reminder that {emi} for your loan with {lender} is due. "
                           f"You can pay instantly here: <pay-link>. Ignore if already paid.",
        "represent_request": f"Your recent auto-debit could not be processed due to low balance. "
                             f"Please keep {emi} ready in your account; we will re-present on <date>.",
        "salary_date_aligned": f"We understand timing can be tight. Shall we align your payment to your "
                               f"salary date? Reply YES and we'll schedule the auto-debit accordingly.",
        "missed_emi_nudge": f"Your EMI is overdue. Clearing it now keeps your credit score healthy and "
                            f"avoids extra charges. Pay here: <pay-link>.",
        "ptp_follow_up": "Confirming the payment you promised. Has it been made? If not, when can we "
                         "expect it? We're here to help you avoid escalation.",
        "firm_reminder": f"Important: {pos} on your loan remains unpaid and is now seriously overdue. "
                         f"Please clear it immediately to avoid further recovery action.",
        "restructure_offer": "Facing genuine difficulty? You may qualify for a restructuring / EMI "
                             "reduction plan. Let's discuss options that fit your cash flow.",
        "settlement_offer": f"You may be eligible for a one-time settlement on your outstanding of {pos}. "
                            f"Speak to us to close this at a reduced, agreed amount.",
        "field_verification": "Field talking points: verify residence/occupancy, confirm identity, assess "
                              "repayment capacity, capture reason for non-payment, secure a written PTP.",
        "skip_trace": "Field / skip-trace: neighbour & reference enquiry, verify current address, flag "
                      "possible absconding, escalate for legal address service if untraceable.",
        "pre_legal_notice": f"PRE-LEGAL NOTICE: {pos} is overdue. Settle within 7 days to avoid legal "
                            f"proceedings and reporting. This is a final opportunity to resolve amicably.",
        "legal_notice": "Issue formal legal demand notice; initiate proceedings per applicable law "
                        "(e.g. Sec 138 for dishonoured instruments) with compliance sign-off.",
    }
    return templates.get(script_type, "Contact the customer regarding the overdue amount.")


# Archetype cadences: (day, channel, script_type). Trimmed/filtered by context.
ARCHETYPES = {
    "digital_first": {
        "label": "Digital-first (low-touch)",
        "cadence": [(0, "whatsapp", "gentle_reminder"), (2, "sms", "represent_request"),
                    (5, "ivr", "missed_emi_nudge"), (9, "voicebot", "firm_reminder")],
    },
    "tele_hybrid": {
        "label": "Tele-calling hybrid",
        "cadence": [(0, "whatsapp", "gentle_reminder"), (1, "ivr", "missed_emi_nudge"),
                    (3, "agent_call", "ptp_follow_up"), (8, "sms", "firm_reminder"),
                    (14, "agent_call", "firm_reminder")],
    },
    "capacity_restructure": {
        "label": "Capacity / restructure",
        "cadence": [(0, "whatsapp", "represent_request"), (1, "sms", "salary_date_aligned"),
                    (4, "agent_call", "restructure_offer"), (12, "settlement", "settlement_offer")],
    },
    "field": {
        "label": "Field / feet-on-street",
        "cadence": [(0, "agent_call", "firm_reminder"), (3, "field_visit", "field_verification"),
                    (12, "field_visit", "skip_trace"), (25, "settlement", "settlement_offer")],
    },
    "legal_hard": {
        "label": "Legal / hard recovery",
        "cadence": [(0, "letter", "pre_legal_notice"), (5, "agent_call", "firm_reminder"),
                    (12, "field_visit", "skip_trace"), (20, "legal", "legal_notice")],
    },
    "hybrid": {
        "label": "Hybrid (multi-channel)",
        "cadence": [(0, "whatsapp", "gentle_reminder"), (2, "ivr", "missed_emi_nudge"),
                    (5, "agent_call", "ptp_follow_up"), (12, "field_visit", "field_verification"),
                    (22, "settlement", "settlement_offer")],
    },
}


def _fmt_money(v):
    if not v:
        return None
    v = float(v)
    if v >= 1e7:
        return f"₹{v/1e7:.2f} Cr"
    if v >= 1e5:
        return f"₹{v/1e5:.2f} L"
    return f"₹{round(v):,}"


def _pick_archetype(ctx):
    """Choose a strategy archetype from the account's signal context."""
    exp = ctx["exposure"]          # 0..1 normalized POS
    hard = ctx["hard_risk"]        # willful refusal / dispute / account closed
    absconding = ctx["absconding"]
    capacity = ctx["capacity"]     # insufficient-funds / low-pay-ratio driven
    reachable = ctx["reachable"]
    early = ctx["early_default"]
    level = ctx["level"]

    if hard and (exp >= 0.5 or ctx["late_stage"]):
        return "legal_hard"
    if absconding or (early and exp >= 0.5 and not reachable):
        return "field"
    if capacity and reachable and not hard:
        return "capacity_restructure"
    if level == "Low" and exp < 0.5:
        return "digital_first"
    if level == "High" and exp >= 0.4:
        return "hybrid"
    return "tele_hybrid"


def recommend(account, sig, kb, level, pay_prob, recoverable, lender="your lender"):
    """
    account: canonicalized dict. sig: this account's signals result.
    Returns a strategy dict: archetype, ordered touches (channel/day/script/tone/
    message), first action, estimated cost, rationale, escalation.
    """
    comp = sig.get("components", {})
    reason_key = kb.reason_key(account.get("bounce_reason"), account.get("field_feedback"))
    stage_sev = kb.stage_severity(account.get("paid_by")) or 0.3
    exposure = comp.get("exposure", 0.4)

    ctx = {
        "level": level,
        "exposure": exposure,
        "hard_risk": reason_key in ("refusal", "dispute", "account_closed"),
        "absconding": reason_key == "absconding",
        "capacity": reason_key in ("insufficient_funds", "limit_exceeded", "mandate_invalid")
                    or (comp.get("recency", 0) < 0.5 and reason_key not in ("refusal", "dispute")),
        "reachable": _reachable(account),
        "early_default": _early_default(account),
        "late_stage": stage_sev >= 0.7,
    }
    arche = _pick_archetype(ctx)
    spec = ARCHETYPES[arche]

    # Cost governance: for small exposure, drop the expensive physical/legal
    # channels (recovery must beat cost -- the core optimization tradeoff).
    small = exposure < 0.35
    mctx = {"pos_fmt": _fmt_money(account.get("total_balance")) or "the outstanding amount",
            "emi_fmt": _fmt_money(account.get("next_installment_amount")) or "your EMI",
            "lender": lender}

    touches = []
    seq = 1
    for day, channel, script in spec["cadence"]:
        if small and channel in ("field_visit", "legal", "agent_call") and arche not in ("legal_hard", "field"):
            continue
        if not ctx["reachable"] and CHANNELS[channel]["digital"] and channel != "sms":
            # unreachable digitally -> keep only SMS/letter/field, skip app-based
            if channel in ("whatsapp", "ivr", "voicebot", "email"):
                continue
        tone = TONE_LADDER[min(len(TONE_LADDER) - 1, CHANNELS[channel]["intensity"] - 1)]
        touches.append({
            "seq": seq, "day": day, "channel": channel, "channel_label": CHANNELS[channel]["label"],
            "script_type": script, "tone": tone, "message": _msg(script, mctx),
            "cost": CHANNELS[channel]["cost"],
        })
        seq += 1
    if not touches:  # safety: always at least one digital touch
        touches = [{"seq": 1, "day": 0, "channel": "sms", "channel_label": "SMS",
                    "script_type": "gentle_reminder", "tone": "empathetic",
                    "message": _msg("gentle_reminder", mctx), "cost": 1}]

    rationale = _rationale(arche, ctx, reason_key)
    return {
        "archetype": arche,
        "label": spec["label"],
        "rationale": rationale,
        "touches": touches,
        "first_action": touches[0]["channel"],
        "first_channel_label": touches[0]["channel_label"],
        "first_script": touches[0]["script_type"],
        "first_tone": touches[0]["tone"],
        "est_cost": sum(t["cost"] for t in touches),
        "channels": sorted({t["channel"] for t in touches}),
        "escalation": _escalation(arche),
        "human_review": arche in ("legal_hard",) or any(t["channel"] == "legal" for t in touches),
    }


def _reachable(a):
    ch = (a.get("preferred_channel") or "").lower()
    mode = (a.get("mode_of_payment") or "").lower()
    if any(k in mode for k in ("upi", "emandate", "nach", "bbps", "online", "digital")):
        return True
    if ch in ("sms", "call", "email", "whatsapp", "phone", "mobile"):
        return True
    rpc = a.get("rpc_rate")
    if rpc is not None and float(rpc) >= 0.4:
        return True
    return not bool(a.get("field_feedback") and "not reachable" in str(a.get("field_feedback")).lower())


def _early_default(a):
    mob = a.get("months_on_book")
    if mob is None and a.get("customer_tenure_years") is not None:
        mob = float(a["customer_tenure_years"]) * 12
    return mob is not None and float(mob) <= 6


def _rationale(arche, ctx, reason_key):
    bits = {
        "legal_hard": "willful non-payment on material exposure at a late stage",
        "field": "unreachable / early-stage default needing physical verification",
        "capacity_restructure": "capacity-driven bounce with a reachable, willing borrower",
        "digital_first": "low exposure and reachable — keep cost low with digital touches",
        "hybrid": "high exposure warranting a multi-channel push",
        "tele_hybrid": "standard reachable delinquent — tele-calling with digital support",
    }
    return bits.get(arche, "balanced multi-channel plan")


def _escalation(arche):
    return {
        "digital_first": "escalate to tele-calling if unpaid after the digital cadence",
        "tele_hybrid": "escalate to field / settlement if promises are not kept",
        "capacity_restructure": "if restructure declined, move to firm tele + settlement",
        "field": "escalate to legal if borrower remains untraceable",
        "legal_hard": "proceed with legal filing after the notice period",
        "hybrid": "escalate to legal / settlement based on response",
    }.get(arche, "review and escalate as needed")
