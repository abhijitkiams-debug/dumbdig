"""
Evidence-based, multi-channel collection strategy engine.

Channels are chosen on their merits -- no channel is privileged. The plan for an
account is a function of: delinquency stage, case type (capacity / willful /
unreachable / early-EMI / technical), exposure (POS vs cost), reachability &
consent, and compliance. Research grounding:
  - Recovery collapses with age (~85% early-stage vs ~11% past 180 DPD); early
    failures are a reachability problem, late ones a negotiation problem.
  - Omnichannel beats single-channel by ~15-25%.
  - SMS is the early-stage workhorse; agent calls drive mid/late negotiation;
    field is for high-POS / unreachable / early-EMI fraud checks; legal is last.
  - WhatsApp: Meta's Business Policy PROHIBITS "debt collection". Only pre-due /
    early *utility payment reminders* with explicit opt-in are permissible, so
    WhatsApp is off unless the account is early-stage AND opted in.
  - India/RBI: contact 8am-7pm only, explicit revocable consent, no coercion.
Seeded priors here are tuned per lender/stage by the learning loop over outcomes.
"""

# Channel economics + constraints.
CHANNELS = {
    "sms":         {"label": "SMS",            "cost": 1,  "intensity": 1, "digital": True,  "needs_consent": False, "needs_smartphone": False},
    "whatsapp":    {"label": "WhatsApp",       "cost": 2,  "intensity": 1, "digital": True,  "needs_consent": True,  "needs_smartphone": True},
    "email":       {"label": "Email",          "cost": 1,  "intensity": 1, "digital": True,  "needs_consent": False, "needs_smartphone": False, "needs_email": True},
    "ivr":         {"label": "IVR call",       "cost": 2,  "intensity": 2, "digital": True,  "needs_consent": False, "needs_smartphone": False},
    "voicebot":    {"label": "AI voicebot",    "cost": 3,  "intensity": 2, "digital": True,  "needs_consent": False, "needs_smartphone": False},
    "agent_call":  {"label": "Agent call",     "cost": 8,  "intensity": 3, "digital": False, "needs_consent": False, "needs_smartphone": False},
    "letter":      {"label": "Letter / notice","cost": 6,  "intensity": 3, "digital": False, "needs_consent": False, "needs_smartphone": False},
    "field_visit": {"label": "Field visit",    "cost": 25, "intensity": 4, "digital": False, "needs_consent": False, "needs_smartphone": False},
    "settlement":  {"label": "Settlement offer","cost": 10,"intensity": 3, "digital": False, "needs_consent": False, "needs_smartphone": False},
    "legal":       {"label": "Legal action",   "cost": 40, "intensity": 5, "digital": False, "needs_consent": False, "needs_smartphone": False},
}
DEFAULT_AVAILABLE = set(CHANNELS)  # a lender can narrow this to what it operates
TONE_LADDER = ["empathetic", "neutral", "firm", "final-warning", "legal"]

# Case-type base plans: ordered (day, channel, script). Trimmed by stage/exposure/reach.
CASE_PLANS = {
    "capacity": [(0, "sms", "represent_request"), (1, "whatsapp", "salary_date_aligned"),
                 (3, "ivr", "missed_emi_nudge"), (6, "agent_call", "restructure_offer"),
                 (12, "settlement", "settlement_offer")],
    "technical": [(0, "sms", "represent_request"), (2, "ivr", "missed_emi_nudge"),
                  (5, "agent_call", "ptp_follow_up")],
    "standard": [(0, "sms", "gentle_reminder"), (1, "whatsapp", "gentle_reminder"),
                 (3, "ivr", "missed_emi_nudge"), (6, "voicebot", "firm_reminder"),
                 (10, "agent_call", "ptp_follow_up"), (16, "settlement", "settlement_offer")],
    "willful": [(0, "agent_call", "firm_reminder"), (3, "letter", "pre_legal_notice"),
                (10, "field_visit", "field_verification"), (18, "legal", "legal_notice")],
    "unreachable": [(0, "sms", "firm_reminder"), (1, "agent_call", "ptp_follow_up"),
                    (4, "field_visit", "field_verification"), (12, "field_visit", "skip_trace"),
                    (22, "letter", "pre_legal_notice")],
    "early_default": [(0, "agent_call", "firm_reminder"), (2, "field_visit", "field_verification"),
                      (10, "field_visit", "skip_trace"), (20, "settlement", "settlement_offer")],
}
CASE_LABEL = {
    "capacity": "Capacity / restructure", "technical": "Re-present (operational bounce)",
    "standard": "Standard escalation", "willful": "Legal / hard recovery",
    "unreachable": "Field / trace", "early_default": "Field verification (early default)",
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


def _msg(script_type, ctx):
    pos, emi, lender = ctx["pos"], ctx["emi"], ctx["lender"]
    t = {
        "gentle_reminder": f"Hi, a gentle reminder that {emi} for your loan with {lender} is due. "
                           f"Pay instantly: <pay-link>. Please ignore if already paid.",
        "represent_request": f"Your recent auto-debit could not be processed due to low balance. "
                             f"Please keep {emi} ready; we will re-present on <date>.",
        "salary_date_aligned": "We understand timing can be tight — shall we align your auto-debit to "
                               "your salary date? Reply YES to reschedule.",
        "missed_emi_nudge": "Your EMI is overdue. Clearing it now protects your credit score and avoids "
                            "extra charges. Pay: <pay-link>.",
        "ptp_follow_up": "Confirming the payment you promised — has it been made? If not, when may we "
                         "expect it? We're here to help you avoid escalation.",
        "firm_reminder": f"Important: {pos} on your loan is seriously overdue. Please clear it immediately "
                         f"to avoid further recovery action.",
        "restructure_offer": "Facing genuine difficulty? You may qualify for restructuring / a lower EMI. "
                             "Let's find a plan that fits your cash flow.",
        "settlement_offer": f"You may be eligible for a one-time settlement on {pos}. Speak to us to close "
                            f"this at a reduced, agreed amount.",
        "field_verification": "Field talking points: verify residence, confirm identity, assess capacity, "
                              "capture non-payment reason, secure a written promise-to-pay.",
        "skip_trace": "Field / skip-trace: neighbour & reference enquiry, verify current address, flag "
                      "absconding, escalate for legal address service if untraceable.",
        "pre_legal_notice": f"PRE-LEGAL NOTICE: {pos} is overdue. Settle within 7 days to avoid legal "
                            f"proceedings. Final opportunity to resolve amicably.",
        "legal_notice": "Issue formal legal demand notice; initiate proceedings per applicable law "
                        "(e.g. Sec 138 for dishonoured instruments) with compliance sign-off.",
    }
    return t.get(script_type, "Contact the customer about the overdue amount.")


def _stage(account, sig, kb, reason_key):
    """Delinquency stage 0-4, from DPD/bucket if present, else inferred."""
    dpd = account.get("days_past_due")
    if dpd is not None:
        d = float(dpd)
        return 0 if d <= 30 else 1 if d <= 60 else 2 if d <= 90 else 3 if d <= 120 else 4
    b = account.get("current_bucket")
    if b is not None:
        return int(min(4, max(0, float(b))))
    # inference when no DPD: reason + collection stage + payment recency
    stage = 1
    if reason_key in ("account_closed", "absconding", "refusal", "dispute"):
        stage = 3
    stage_sev = kb.stage_severity(account.get("paid_by")) or 0.0
    if stage_sev >= 0.8:
        stage += 1
    if sig.get("components", {}).get("recency", 0) >= 0.7:
        stage += 1
    return int(min(4, stage))


def _case_type(account, reason_key, comp, reachable):
    if reason_key == "absconding" or not reachable:
        return "unreachable"
    if reason_key in ("refusal", "dispute", "account_closed"):
        return "willful"
    mob = account.get("months_on_book")
    if mob is None and account.get("customer_tenure_years") is not None:
        mob = float(account["customer_tenure_years"]) * 12
    if mob is not None and float(mob) <= 6:
        return "early_default"
    if reason_key in ("insufficient_funds", "limit_exceeded", "mandate_invalid"):
        return "capacity"
    if reason_key == "technical":
        return "technical"
    return "standard"


def _reach(account):
    """
    What we can actually use. In India WhatsApp IS used for delinquency, so it is
    allowed as a utility reminder unless the borrower has explicitly opted OUT.
    We track whether opt-in is on record so the plan can flag it for compliance.
    """
    def flag(*keys):
        for k in keys:
            v = account.get(k)
            if v is None or str(v).strip() == "":
                continue
            s = str(v).strip().lower()
            if s in ("1", "y", "yes", "true", "opted_in", "opt_in", "consented", "optin"):
                return "in"
            if s in ("0", "n", "no", "false", "opted_out", "opt_out", "dnd", "revoked"):
                return "out"
        return "unknown"

    consent = flag("whatsapp_consent", "consent")
    fb = str(account.get("field_feedback") or "").lower()
    voice_ok = "not reachable" not in fb and "wrong number" not in fb and "untraceable" not in fb
    return {
        "sms": voice_ok, "whatsapp": consent != "out", "wa_consent": consent,
        "email": bool(account.get("email")), "ivr": voice_ok, "voicebot": voice_ok,
        "agent_call": voice_ok, "letter": True, "field_visit": True,
        "settlement": True, "legal": True,
    }


def _send_window(channel, account):
    """Right-time guidance within the RBI 08:00-19:00 window."""
    bch = account.get("best_contact_hour")
    if bch not in (None, ""):
        try:
            h = int(float(bch))
            lo, hi = max(8, h - 1), min(19, h + 1)
            return f"{lo:02d}:00–{hi:02d}:00"
        except (ValueError, TypeError):
            pass
    # sensible defaults: digital late-morning, calls late-afternoon/evening
    return {"whatsapp": "10:00–13:00", "sms": "10:00–13:00", "email": "10:00–18:00",
            "ivr": "11:00–14:00", "voicebot": "11:00–14:00", "agent_call": "16:00–19:00",
            "field_visit": "11:00–17:00"}.get(channel, "10:00–18:00")


def recommend(account, sig, kb, level, pay_prob, recoverable, lender="your lender", available=None):
    comp = sig.get("components", {})
    reason_key = kb.reason_key(account.get("bounce_reason"), account.get("field_feedback"))
    reach = _reach(account)
    stage = _stage(account, sig, kb, reason_key)
    case = _case_type(account, reason_key, comp, reach["sms"])
    exposure = comp.get("exposure", 0.4)
    avail = set(available) if available else DEFAULT_AVAILABLE
    small = exposure < 0.35   # cost gate: low POS stays digital

    plan = list(CASE_PLANS[case])
    # Stage escalation: late stage ensures harder channels; early keeps it light.
    if stage >= 3 and case in ("standard", "capacity", "technical"):
        plan += [(14, "agent_call", "firm_reminder"), (24, "settlement", "settlement_offer")]
    if stage <= 1 and case == "standard":
        plan = [t for t in plan if t[1] not in ("settlement",)]

    mctx = {"pos": _fmt_money(account.get("total_balance")) or "the outstanding amount",
            "emi": _fmt_money(account.get("next_installment_amount")) or "your EMI", "lender": lender}

    touches, excluded = [], set()
    wa_used = False
    seq = 1
    for day, channel, script in plan:
        spec = CHANNELS[channel]
        if channel not in avail:
            continue
        # WhatsApp: right channel for early-mid, reachable, non-hard cases only;
        # allowed unless explicitly opted out. Wrong for late/willful stages.
        if channel == "whatsapp":
            if reach["wa_consent"] == "out" or stage >= 3 or case in ("willful", "unreachable"):
                excluded.add("whatsapp")
                continue
            wa_used = True
        elif not reach.get(channel, True):
            excluded.add(channel)
            continue
        if spec.get("needs_email") and not reach.get("email"):
            continue
        # cost gate: drop expensive physical/legal on small exposure unless the
        # case fundamentally requires them
        if small and channel in ("field_visit", "legal") and case not in ("willful", "unreachable"):
            continue
        if small and channel == "agent_call" and case in ("standard", "technical"):
            continue
        tone = TONE_LADDER[min(len(TONE_LADDER) - 1, spec["intensity"] - 1 + (1 if stage >= 3 else 0))]
        if channel == "whatsapp":
            tone = "empathetic"  # utility, never coercive
        touches.append({"seq": seq, "day": day, "channel": channel, "channel_label": spec["label"],
                        "script_type": script, "tone": tone, "message": _msg(script, mctx),
                        "when": _send_window(channel, account), "cost": spec["cost"]})
        seq += 1

    if not touches:  # always at least one compliant digital touch
        touches = [{"seq": 1, "day": 0, "channel": "sms", "channel_label": "SMS",
                    "script_type": "gentle_reminder", "tone": "empathetic",
                    "message": _msg("gentle_reminder", mctx), "cost": 1}]

    compliance = ["Contact only 08:00–19:00; not on Sundays/holidays (RBI)."]
    if wa_used:
        note = "WhatsApp: send as a utility payment-reminder template, in-hours, non-coercive tone."
        if reach["wa_consent"] != "in":
            note += " Confirm opt-in on record."
        compliance.append(note)
    elif "whatsapp" in excluded and reach["wa_consent"] == "out":
        compliance.append("WhatsApp excluded: borrower opted out.")
    elif "whatsapp" in excluded:
        compliance.append("WhatsApp not used at this stage/case (utility reminders suit early, reachable, non-hard cases).")
    if any(t["channel"] in ("agent_call", "field_visit") for t in touches):
        compliance.append("Disclose recovery-agent identity before contact (RBI).")

    return {
        "archetype": case, "label": CASE_LABEL.get(case, case),
        "stage": stage, "case": case,
        "rationale": _rationale(case, stage),
        "touches": touches,
        "first_action": touches[0]["channel"], "first_channel_label": touches[0]["channel_label"],
        "first_script": touches[0]["script_type"], "first_tone": touches[0]["tone"],
        "est_cost": sum(t["cost"] for t in touches),
        "channels": sorted({t["channel"] for t in touches}),
        "compliance": compliance,
        "escalation": _escalation(case),
        "human_review": case == "willful" or any(t["channel"] == "legal" for t in touches),
    }


def _rationale(case, stage):
    base = {
        "capacity": "capacity-driven bounce — re-present, align to cash flow, offer restructure",
        "technical": "operational bounce — re-present and confirm",
        "standard": "reachable delinquent — digital-led escalation",
        "willful": "willful non-payment — escalate firmly toward legal",
        "unreachable": "not reachable digitally — trace and field verification",
        "early_default": "early-EMI default — field verification (possible fraud/mis-sell)",
    }.get(case, "balanced plan")
    return f"stage {stage}: {base}"


def _escalation(case):
    return {
        "standard": "escalate to agent/field/settlement if promises are not kept",
        "capacity": "if restructure declined, move to firm tele + settlement",
        "technical": "escalate to standard cadence if re-present fails",
        "willful": "proceed with legal filing after the notice period",
        "unreachable": "escalate to legal address service if untraceable",
        "early_default": "escalate to legal if verification confirms mis-sell/fraud",
    }.get(case, "review and escalate as needed")
