"""
Mamdani-style fuzzy inference for a human-readable Debt Priority Level (DPL).

This is the paper's "human-in-the-loop" layer (Section 4.3.3.2): it turns raw
model outputs and financial exposure into an interpretable Low/Medium/High
priority plus a plain-English explanation -- the thing a collector or a
regulator can actually read. Implemented with triangular membership functions
and a centroid defuzzifier, no external fuzzy library required.
"""


def _tri(x, a, b, c):
    """
    Triangular membership value at x for triangle (a,b,c).

    The peak check comes first so "shoulder" sets, where b==c (e.g. High = the
    right shoulder (0.5,1,1)) or b==a (Low = (0,0,0.5)), return 1 at the peak
    instead of 0. Without this, a value that clips to exactly the edge (1.0)
    would get zero membership in every set and fire no rule.
    """
    if x == b:
        return 1.0
    if x <= a or x >= c:
        return 0.0
    if x < b:
        return (x - a) / (b - a)
    return (c - x) / (c - b)


def _memberships(x, sets):
    """sets: dict term -> (a,b,c). Returns dict term -> membership degree."""
    return {term: _tri(x, *tri) for term, tri in sets.items()}


# Input variables are normalized to [0,1] before fuzzification so the same
# membership functions apply regardless of currency or portfolio size.
LOW = (0.0, 0.0, 0.5)
MED = (0.2, 0.5, 0.8)
HIGH = (0.5, 1.0, 1.0)
TERMS = {"low": LOW, "medium": MED, "high": HIGH}

# Output DPL universe (crisp 0..100). Three output fuzzy sets.
OUT_SETS = {
    "low": (0, 0, 45),
    "medium": (25, 50, 75),
    "high": (55, 100, 100),
}


def infer_priority(prob_payment, expected_payment_norm, past_due_norm):
    """
    Inputs (all in [0,1]):
      prob_payment          -- APP model probability of actual payment
      expected_payment_norm -- expected recoverable value, normalized
      past_due_norm         -- outstanding overdue amount, normalized
    Returns: (dpl_score 0..100, level 'Low'|'Medium'|'High', reasons list)
    """
    p = _memberships(prob_payment, TERMS)
    e = _memberships(expected_payment_norm, TERMS)
    d = _memberships(past_due_norm, TERMS)

    # Expert rule base (Section 4.3.3.2). Each rule fires with a strength and
    # votes for a DPL output set. We track which rule fired hardest for the
    # explanation string.
    rules = [
        # (strength, output_term, human_reason)
        (min(p["high"], e["high"]), "high", "high probability of payment on a high expected value"),
        (min(p["high"], d["high"]), "high", "likely to pay and carrying a large past-due balance"),
        (min(p["high"], e["medium"]), "medium", "likely to pay a moderate recoverable amount"),
        (min(p["medium"], d["high"]), "medium", "moderate payment odds but significant arrears"),
        (min(p["medium"], e["medium"]), "medium", "moderate payment odds and moderate recoverable value"),
        (min(p["high"], e["low"]), "low", "very likely to pay but low recoverable value; a low-touch reminder suffices"),
        (min(p["medium"], e["low"]), "low", "moderate odds on a small balance; low-touch"),
        (min(p["low"], d["medium"]), "low", "low payment odds with only moderate arrears"),
        (min(e["low"], d["high"]), "low", "low recoverable value despite high arrears"),
        (min(p["low"], e["low"]), "low", "low payment odds and low recoverable value"),
    ]

    # Aggregate by max over each output term (Mamdani max-aggregation).
    agg = {"low": 0.0, "medium": 0.0, "high": 0.0}
    for strength, term, _ in rules:
        agg[term] = max(agg[term], strength)

    # Centroid defuzzification over a sampled output universe.
    num = 0.0
    den = 0.0
    for xi in range(0, 101):
        mu = max(min(agg[term], _tri(xi, *OUT_SETS[term])) for term in OUT_SETS)
        num += xi * mu
        den += mu
    dpl = (num / den) if den else 0.0

    level = "High" if dpl >= 60 else ("Medium" if dpl >= 35 else "Low")

    # Explanation: the strongest firing rule(s).
    fired = sorted([r for r in rules if r[0] > 0.05], key=lambda r: -r[0])[:2]
    reasons = [r[2] for r in fired] or ["insufficient signal; defaulted to low priority"]
    return round(dpl, 1), level, reasons
