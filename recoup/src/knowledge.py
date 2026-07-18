"""
Aayudh knowledge base: domain intelligence that is itself learnable.

Holds the collections know-how the flexible scorer needs to interpret
heterogeneous lender data -- bounce-reason severities, mandate/presentation
vocab, collection-stage meaning of PAID-BY, per-signal weight multipliers, and
action effectiveness. All of it starts from sensible defaults and is updated
over time from human feedback and real outcomes (see learner.py). Serializable
to/from JSON so it persists in the SQLite store.
"""

import re

# Reason keyword -> (severity 0..1, canonical key). Severity is a *prior*; the
# learner recalibrates it from observed pay rates. Willful/hard first, then
# capacity, then technical/operational.
DEFAULT_REASON_RULES = [
    (("refus", "declin", "not interested", "unwilling", "wilful", "willful", "denied"), 0.95, "refusal"),
    (("abscond", "untraceable", "skip", "not traceable"), 0.95, "absconding"),
    (("account closed", "acc closed", "closed account"), 0.90, "account_closed"),
    (("stop payment", "stoppayment", "payment stopped", "withdrawal stopped"), 0.88, "stop_payment"),
    (("dispute", "not liable", "fraud"), 0.85, "dispute"),
    (("death", "deceased", "expired"), 0.80, "deceased"),
    (("invalid umrn", "inactive mandate", "mandate not", "no mandate", "umrn"), 0.65, "mandate_invalid"),
    (("insuffic", "no funds", "balance insufficient", "funds insufficient"), 0.45, "insufficient_funds"),
    (("exceed", "limit"), 0.45, "limit_exceeded"),
    (("signature", "mismatch", "technical", "image", "cts", "micr", "connectivity", "stale", "amount mismatch"), 0.25, "technical"),
]

# PAID-BY / allocation -> collection stage severity (later stage = higher).
DEFAULT_STAGE_RULES = [
    (("legal", "litigation", "arbitration"), 0.95, "legal"),
    (("dma", "agency", "fos", "field"), 0.80, "agency"),
    (("co allocation", "collection", "recovery", "tele"), 0.60, "tele_collection"),
    (("bacl", "branch"), 0.45, "branch"),
    (("cc", "call center", "callcenter", "touchfree", "self", "online", "digital"), 0.25, "self_serve"),
]

NEGATIVE_STATUS = ("bounce", "return", "unpaid", "dishonour", "dishonor", "fail", "reject",
                   "cancel", "inactive", "expire", "closed", "npa", "overdue", "spresent")

GLOSSARY = {
    "DPD": "Days Past Due — days a payment is overdue.",
    "Bucket": "Delinquency stage grouped by DPD (0/30/60/90/120+).",
    "POS": "Principal Outstanding — the amount still to be recovered.",
    "Vintage": "Loan age since disbursement; early-EMI defaults are a red flag.",
    "Bounce Rate": "Share of presentations that failed (bounced).",
    "Collection Efficiency": "Actual collections vs. total due.",
    "DSCR": "Debt Service Coverage Ratio — ability to service debt from cash flow.",
    "Mandate": "Auto-debit (NACH/e-mandate) authorisation status.",
}


def _canon(s):
    return re.sub(r"\s+", " ", (s or "").strip().lower())


class KnowledgeBase:
    def __init__(self, state=None):
        state = state or {}
        # learned overrides: reason_key -> {"sev": float, "paid": n, "total": n}
        self.reason_learned = state.get("reason_learned", {})
        self.stage_learned = state.get("stage_learned", {})
        # signal weight multipliers (learned), default 1.0
        self.signal_weights = state.get("signal_weights", {})
        # action effectiveness: action -> {"success": n, "total": n}
        self.action_stats = state.get("action_stats", {})
        self.version = state.get("version", 1)

    # ---- lookups used by the scorer ---------------------------------------
    def reason_severity(self, *texts):
        s = _canon(" ".join(t or "" for t in texts))
        if not s:
            return None
        for keys, sev, key in DEFAULT_REASON_RULES:
            if any(k in s for k in keys):
                learned = self.reason_learned.get(key)
                return learned["sev"] if learned else sev
        return 0.4  # unknown adverse-looking reason

    def reason_key(self, *texts):
        s = _canon(" ".join(t or "" for t in texts))
        for keys, _, key in DEFAULT_REASON_RULES:
            if any(k in s for k in keys):
                return key
        return "other" if s else None

    def stage_severity(self, paid_by):
        s = _canon(paid_by)
        if not s:
            return None
        for keys, sev, key in DEFAULT_STAGE_RULES:
            if any(k in s for k in keys):
                learned = self.stage_learned.get(key)
                return learned["sev"] if learned else sev
        return 0.4

    def stage_key(self, paid_by):
        s = _canon(paid_by)
        for keys, _, key in DEFAULT_STAGE_RULES:
            if any(k in s for k in keys):
                return key
        return "other" if s else None

    def status_is_negative(self, text):
        s = _canon(text)
        return bool(s) and any(w in s for w in NEGATIVE_STATUS)

    def weight(self, signal):
        return float(self.signal_weights.get(signal, 1.0))

    def action_success_rate(self, action, prior=0.4):
        st = self.action_stats.get(action)
        if not st or st["total"] == 0:
            return prior
        # smoothed
        return (st["success"] + prior * 3) / (st["total"] + 3)

    # ---- learning updates (called by learner.py) --------------------------
    def observe_reason_outcome(self, key, paid):
        if not key:
            return
        rec = self.reason_learned.setdefault(key, {"sev": None, "paid": 0, "total": 0})
        rec["paid"] += 1 if paid else 0
        rec["total"] += 1
        pay_rate = rec["paid"] / rec["total"]
        # higher pay rate -> lower severity. Blend toward evidence as n grows.
        evidence = 1.0 - pay_rate
        base = next((sev for keys, sev, k in DEFAULT_REASON_RULES if k == key), 0.4)
        alpha = min(0.8, rec["total"] / (rec["total"] + 10.0))
        rec["sev"] = round((1 - alpha) * base + alpha * evidence, 3)

    def observe_stage_outcome(self, key, paid):
        if not key:
            return
        rec = self.stage_learned.setdefault(key, {"sev": None, "paid": 0, "total": 0})
        rec["paid"] += 1 if paid else 0
        rec["total"] += 1
        alpha = min(0.8, rec["total"] / (rec["total"] + 10.0))
        base = next((sev for keys, sev, k in DEFAULT_STAGE_RULES if k == key), 0.4)
        rec["sev"] = round((1 - alpha) * base + alpha * (1.0 - rec["paid"] / rec["total"]), 3)

    def observe_action_outcome(self, action, success):
        st = self.action_stats.setdefault(action, {"success": 0, "total": 0})
        st["success"] += 1 if success else 0
        st["total"] += 1

    def nudge_signal_weight(self, signal, factor):
        """Human feedback that emphasises/deemphasises a signal (bounded)."""
        cur = self.signal_weights.get(signal, 1.0)
        self.signal_weights[signal] = round(max(0.3, min(2.5, cur * factor)), 3)

    def to_dict(self):
        return {
            "version": self.version,
            "reason_learned": self.reason_learned,
            "stage_learned": self.stage_learned,
            "signal_weights": self.signal_weights,
            "action_stats": self.action_stats,
        }
