"""
Recoup pipeline orchestrator.

Layer 1 (Rule Extraction) -> behavioral segment via KMeans.
Layer 2 (Prediction)      -> Promise-to-Pay + Actual-Payment probabilities (RF).
Layer 3 (Optimization)    -> fuzzy Debt Priority Level, TOPSIS rank,
                             AHP next-best-action, field routing.

Produces one explainable worklist row per account plus an audit trail, so a
prediction is always traceable to the action it justifies.
"""

import numpy as np
from sklearn.cluster import KMeans
from sklearn.ensemble import RandomForestClassifier
from sklearn.metrics import roc_auc_score, accuracy_score, f1_score
from sklearn.model_selection import train_test_split

import fuzzy
import ahp
from topsis import topsis
from routing import plan_routes

FEATURES = [
    "age", "customer_tenure_years", "num_co_borrowers", "lent_amount", "interest",
    "term_months", "total_balance", "next_installment_amount", "past_due_amount",
    "current_bucket", "days_past_due", "avg_pay_amount_ever", "last_pay_amount",
    "days_since_last_payment", "num_payments_12m", "pay_ratio_6m", "std_pay_amount",
    "contact_attempts_6m", "rpc_rate", "promises_made_6m", "promises_kept_6m",
    "refusals_6m", "days_since_last_contact", "best_contact_hour",
]

SEGMENT_NAMES = ["self_cured", "lazy_payer", "delinquent", "defaulter"]

# Which action best serves a debtor's stated channel preference.
CHANNEL_ACTION = {"sms": "sms_reminder", "call": "agent_call", "email": "email", "letter": "letter"}


def choose_action(level, segment, bucket, prob_app, refusals, pref_channel, action_pref):
    """
    Pick the next best action for one debtor. Stays strictly inside the
    compliance playbook for the fuzzy priority level, but personalizes within
    it: honor the debtor's reachable channel, and de-escalate away from calls
    when the debtor has been repeatedly refusing (contact fatigue).
    Returns (action, rationale).
    """
    allowed = ahp.PLAYBOOK[level]
    ahp_choice = next((a for a in action_pref if a in allowed), allowed[0])

    # Contact fatigue: a High-priority debtor who keeps refusing calls is better
    # moved to a settlement offer than hit with another agent call.
    if level == "High" and refusals >= 3 and "settlement_offer" in allowed:
        return "settlement_offer", "repeated refusals -> offer settlement instead of another call"

    # Hardened defaulters deep in delinquency: prefer settlement where allowed.
    if segment == "defaulter" and bucket >= 3 and prob_app < 0.35 and "settlement_offer" in allowed:
        return "settlement_offer", "low-probability defaulter in late bucket -> settlement"

    # Channel fit: if the debtor's preferred channel maps to an allowed action,
    # and it is a reasonable (cheap/reachable) choice, use it.
    pref_action = CHANNEL_ACTION.get((pref_channel or "").lower())
    if pref_action in allowed:
        return pref_action, f"reachable on preferred channel ({pref_channel})"

    return ahp_choice, "AHP-preferred action within compliance playbook"


def _matrix(rows, medians):
    """Build feature matrix, imputing missing values with training medians."""
    X = np.zeros((len(rows), len(FEATURES)))
    for i, r in enumerate(rows):
        for j, f in enumerate(FEATURES):
            v = r.get(f)
            X[i, j] = float(v) if v is not None else medians[j]
    return X


def _medians(rows):
    med = []
    for f in FEATURES:
        vals = [float(r[f]) for r in rows if r.get(f) is not None]
        med.append(float(np.median(vals)) if vals else 0.0)
    return med


def _minmax(v, lo, hi):
    return 0.0 if hi <= lo else float(np.clip((v - lo) / (hi - lo), 0, 1))


def train_models(train_rows):
    """Train P2P and APP classifiers on labeled rows. Returns models + metrics."""
    medians = _medians(train_rows)
    X = _matrix(train_rows, medians)
    out = {"medians": medians, "models": {}, "metrics": {}}

    for label in ("label_promise_to_pay", "label_actual_payment"):
        y = np.array([int(r[label]) for r in train_rows if r.get(label) is not None])
        if len(y) != len(train_rows) or len(set(y)) < 2:
            continue
        Xtr, Xte, ytr, yte = train_test_split(X, y, test_size=0.25, random_state=42, stratify=y)
        clf = RandomForestClassifier(n_estimators=200, max_depth=12, random_state=42, n_jobs=-1)
        clf.fit(Xtr, ytr)
        proba = clf.predict_proba(Xte)[:, 1]
        pred = (proba >= 0.5).astype(int)
        out["models"][label] = clf
        out["metrics"][label] = {
            "auc": round(float(roc_auc_score(yte, proba)), 4),
            "accuracy": round(float(accuracy_score(yte, pred)), 4),
            "f1": round(float(f1_score(yte, pred)), 4),
            "n_test": int(len(yte)),
        }
    return out


SEG_FEATS = ["pay_ratio_6m", "rpc_rate", "days_past_due", "promises_kept_6m", "days_since_last_payment"]


def fit_segmenter(train_rows, medians):
    """Fit Layer-1 behavioral clustering once; returns a reusable segmenter."""
    idx = [FEATURES.index(f) for f in SEG_FEATS]
    Xtr = _matrix(train_rows, medians)[:, idx]
    mu = Xtr.mean(axis=0)
    sd = Xtr.std(axis=0) + 1e-9
    km = KMeans(n_clusters=4, n_init=10, random_state=42).fit((Xtr - mu) / sd)
    # Order clusters by "health" (higher pay_ratio & rpc, lower dpd) -> name them.
    centers = km.cluster_centers_
    health = centers[:, 0] + centers[:, 1] - centers[:, 2] - centers[:, 4]
    order = np.argsort(-health)  # healthiest first
    name_map = {int(order[i]): SEGMENT_NAMES[i] for i in range(4)}
    return {"km": km, "mu": mu, "sd": sd, "name_map": name_map, "idx": idx}


def apply_segmenter(seg, target_rows, medians):
    Xte = _matrix(target_rows, medians)[:, seg["idx"]]
    labels = seg["km"].predict((Xte - seg["mu"]) / seg["sd"])
    return [seg["name_map"][int(l)] for l in labels]


class Recoup:
    """Fit the models + segmenter once, then score any number of portfolios."""

    def __init__(self):
        self.trained = None
        self.segmenter = None

    def fit(self, train_rows):
        self.trained = train_models(train_rows)
        self.segmenter = fit_segmenter(train_rows, self.trained["medians"])
        return self

    @property
    def metrics(self):
        return self.trained["metrics"] if self.trained else {}

    def score(self, target_rows, criteria_matrix=None, n_collectors=3, top_k_field=None):
        return _score(self.trained, self.segmenter, target_rows,
                      criteria_matrix, n_collectors, top_k_field)


def score_portfolio(train_rows, target_rows, criteria_matrix=None, n_collectors=3,
                    top_k_field=None):
    """Convenience: fit on train_rows, then score target_rows in one call."""
    model = Recoup().fit(train_rows)
    return model.score(target_rows, criteria_matrix, n_collectors, top_k_field)


def _score(trained, segmenter, target_rows, criteria_matrix=None, n_collectors=3,
           top_k_field=None):
    """
    Score target_rows with an already-fitted model + segmenter.
    Returns a results dict ready to serialize.
    """
    medians = trained["medians"]
    Xt = _matrix(target_rows, medians)

    m_p2p = trained["models"].get("label_promise_to_pay")
    m_app = trained["models"].get("label_actual_payment")
    prob_p2p = m_p2p.predict_proba(Xt)[:, 1] if m_p2p else np.full(len(target_rows), 0.5)
    prob_app = m_app.predict_proba(Xt)[:, 1] if m_app else np.full(len(target_rows), 0.5)

    segments = apply_segmenter(segmenter, target_rows, medians)

    # Amount at stake for recovery per account. In real collections books the
    # recoverable amount is the Principal Outstanding (POS / total balance); we
    # fall back to arrears (past due) and then the next installment when POS is
    # not provided. Expected recovery is that amount weighted by the payment
    # probability.
    total_bal = np.array([float(r.get("total_balance") or 0.0) for r in target_rows])
    next_inst = np.array([float(r.get("next_installment_amount") or medians[FEATURES.index("next_installment_amount")]) for r in target_rows])
    past_due = np.array([float(r.get("past_due_amount") or 0.0) for r in target_rows])
    recoverable = np.where(total_bal > 0, total_bal, np.where(past_due > 0, past_due, next_inst))
    expected = prob_app * recoverable

    # For prioritization, the "arrears/exposure" cost criterion uses past due if
    # present, otherwise POS (so hard-bucket accounts still register exposure).
    exposure = np.where(past_due > 0, past_due, total_bal)

    # Normalization bounds for fuzzy inputs (portfolio-relative).
    e_lo, e_hi = float(expected.min()), float(np.percentile(expected, 98))
    d_lo, d_hi = float(exposure.min()), float(np.percentile(exposure, 98))

    # ---- TOPSIS ranking over 5 criteria (paper Section 5.3.1) -------------
    last_pay = np.array([float(r.get("last_pay_amount") or 0.0) for r in target_rows])
    crit = np.column_stack([recoverable, last_pay, exposure, prob_app, expected])
    weights = [0.15, 0.05, 0.15, 0.30, 0.35]
    benefit = [True, True, True, True, True]  # higher recoverable/exposure => higher priority
    topsis_scores = topsis(crit, weights, benefit)

    # ---- AHP action ordering (shared across the portfolio) ----------------
    ranked_actions, ahp_cr = ahp.rank_actions(criteria_matrix)
    action_pref = [a for a, _ in ranked_actions]

    worklist = []
    for i, r in enumerate(target_rows):
        e_norm = _minmax(expected[i], e_lo, e_hi)
        d_norm = _minmax(past_due[i], d_lo, d_hi)
        dpl, level, reasons = fuzzy.infer_priority(float(prob_app[i]), e_norm, d_norm)

        # Next best action: AHP ordering, personalized within the compliance
        # playbook allowed by the fuzzy priority level.
        nba, nba_why = choose_action(
            level, segments[i], int(r.get("current_bucket") or 0), float(prob_app[i]),
            int(r.get("refusals_6m") or 0), r.get("preferred_channel"), action_pref,
        )

        worklist.append({
            "account_id": r.get("account_id"),
            "region": r.get("region") or "",
            "segment": segments[i],
            "current_bucket": int(r.get("current_bucket") or 0),
            "prob_promise_to_pay": round(float(prob_p2p[i]), 4),
            "prob_actual_payment": round(float(prob_app[i]), 4),
            "pos": round(float(total_bal[i]), 2),
            "past_due_amount": round(float(past_due[i]), 2),
            "next_installment_amount": round(float(next_inst[i]), 2),
            "recoverable_amount": round(float(recoverable[i]), 2),
            "expected_payment": round(float(expected[i]), 2),
            "priority_score": round(float(dpl), 1),
            "priority_level": level,
            "topsis_score": round(float(topsis_scores[i]), 4),
            "next_best_action": nba,
            "preferred_channel": r.get("preferred_channel") or "",
            "best_contact_hour": r.get("best_contact_hour"),
            "geo_x": r.get("geo_x"),
            "geo_y": r.get("geo_y"),
            "reasons": reasons,
            "audit": {
                "model_p2p": "RandomForest(200,d12)" if m_p2p else "prior=0.5",
                "model_app": "RandomForest(200,d12)" if m_app else "prior=0.5",
                "fuzzy_inputs": {
                    "prob_actual_payment": round(float(prob_app[i]), 4),
                    "expected_recovery_norm": round(e_norm, 4),
                    "exposure_norm": round(d_norm, 4),
                },
                "action_from": "AHP x compliance playbook",
                "action_rationale": nba_why,
                "human_review_required": level == "High" or nba == "legal_escalation",
            },
        })

    # Rank worklist by TOPSIS score (primary) desc.
    worklist.sort(key=lambda w: -w["topsis_score"])
    for rank, w in enumerate(worklist, 1):
        w["rank"] = rank

    # ---- Field routing over the top field-eligible, geo-located accounts --
    field_pool = [w for w in worklist
                  if w["priority_level"] in ("High", "Medium")
                  and w["geo_x"] is not None and w["geo_y"] is not None]
    if top_k_field:
        field_pool = field_pool[:top_k_field]
    routes = plan_routes(
        [{"account_id": w["account_id"], "geo_x": float(w["geo_x"]),
          "geo_y": float(w["geo_y"]), "expected_payment": w["expected_payment"]}
         for w in field_pool],
        n_collectors=n_collectors,
    )

    # ---- Portfolio-level summary -----------------------------------------
    levels = {"High": 0, "Medium": 0, "Low": 0}
    seg_counts = {s: 0 for s in SEGMENT_NAMES}
    action_counts = {}
    for w in worklist:
        levels[w["priority_level"]] += 1
        seg_counts[w["segment"]] = seg_counts.get(w["segment"], 0) + 1
        action_counts[w["next_best_action"]] = action_counts.get(w["next_best_action"], 0) + 1

    summary = {
        "accounts": len(worklist),
        "total_outstanding": round(float(recoverable.sum()), 2),
        "total_past_due": round(float(past_due.sum()), 2),
        "total_expected_recovery": round(float(expected.sum()), 2),
        "priority_breakdown": levels,
        "segment_breakdown": seg_counts,
        "action_breakdown": dict(sorted(action_counts.items(), key=lambda t: -t[1])),
        "field_visits_planned": sum(rt["num_stops"] for rt in routes),
        "field_expected_recovery": round(sum(rt["expected_recovery"] for rt in routes), 2),
    }

    # Note any uploaded columns we could not recognize -> which get imputed.
    return {
        "model_metrics": trained["metrics"],
        "ahp_consistency_ratio": ahp_cr,
        "ahp_action_ranking": ranked_actions,
        "summary": summary,
        "worklist": worklist,
        "routes": routes,
    }
