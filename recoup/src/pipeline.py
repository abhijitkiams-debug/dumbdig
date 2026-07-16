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

import ahp
import learner
import signals
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


def choose_action(level, segment, bucket, prob_app, refusals, pref_channel, action_pref,
                  hard_risk=False, early_default=False):
    """
    Pick the next best action for one debtor. Stays strictly inside the
    compliance playbook for the priority level, but personalizes within it:
    escalate refused/hard-risk accounts, send early defaults to a field visit,
    honor the debtor's reachable channel, and de-escalate away from calls under
    contact fatigue. Returns (action, rationale).
    """
    allowed = ahp.PLAYBOOK[level]
    ahp_choice = next((a for a in action_pref if a in allowed), allowed[0])

    # Early-stage (first-EMI) default is a field-verification priority: contact
    # directly / send for a field check before deciding on harder measures.
    if early_default and "agent_call" in allowed:
        return "agent_call", "early-stage default -> direct agent contact / field check"

    # Willful refusal / hard risk on a worked account -> settlement, then legal.
    if hard_risk and level in ("High", "Medium"):
        if "settlement_offer" in allowed:
            return "settlement_offer", "refused / hard-risk account -> offer settlement"
        if "legal_escalation" in allowed:
            return "legal_escalation", "refused to pay and no softer option remains -> legal"

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

    def score(self, target_rows, criteria_matrix=None, n_collectors=3, top_k_field=None,
              lender="default"):
        return _score(self.trained, self.segmenter, target_rows,
                      criteria_matrix, n_collectors, top_k_field, lender)


def score_portfolio(train_rows, target_rows, criteria_matrix=None, n_collectors=3,
                    top_k_field=None, lender="default"):
    """Convenience: fit on train_rows, then score target_rows in one call."""
    model = Recoup().fit(train_rows)
    return model.score(target_rows, criteria_matrix, n_collectors, top_k_field, lender)


def _score(trained, segmenter, target_rows, criteria_matrix=None, n_collectors=3,
           top_k_field=None, lender="default"):
    """
    Score target_rows with an already-fitted model + segmenter.
    Returns a results dict ready to serialize.
    """
    medians = trained["medians"]
    n = len(target_rows)
    segments = apply_segmenter(segmenter, target_rows, medians)

    # ---- Flexible, signal-based risk-priority (KB-aware) ------------------
    kb = learner.load_kb(lender)
    sig_results, sig_meta = signals.score_portfolio(target_rows, kb)

    # Recoverable amount is Principal Outstanding first (POS), then arrears,
    # then installment. Expected recovery = recoverable x payment likelihood.
    total_bal = np.array([float(r.get("total_balance") or 0.0) for r in target_rows])
    next_inst = np.array([float(r.get("next_installment_amount") or medians[FEATURES.index("next_installment_amount")]) for r in target_rows])
    past_due = np.array([float(r.get("past_due_amount") or 0.0) for r in target_rows])
    recoverable = np.where(total_bal > 0, total_bal, np.where(past_due > 0, past_due, next_inst))

    # Feature vectors for the learned model (same flexible signal space).
    feature_rows = []
    for i, r in enumerate(target_rows):
        comp = dict(sig_results[i]["components"])
        comp["_reason_key"] = kb.reason_key(r.get("bounce_reason"), r.get("field_feedback"))
        comp["_stage_key"] = kb.stage_key(r.get("paid_by"))
        feature_rows.append(comp)

    # Payment likelihood: the learned model if this lender has one, else a
    # heuristic (higher risk -> lower likelihood) until outcomes arrive.
    learned = learner.pay_likelihood(lender, feature_rows)
    if learned is not None:
        pay_prob = np.clip(learned, 0.02, 0.98)
        pay_source = "learned model"
    else:
        pay_prob = np.array([float(np.clip(1 - 0.75 * sig_results[i]["composite"], 0.05, 0.95)) for i in range(n)])
        pay_source = "heuristic (no outcomes yet)"
    expected = pay_prob * recoverable

    # ---- TOPSIS ranking (recovery value x likelihood) ---------------------
    last_pay = np.array([float(r.get("last_pay_amount") or 0.0) for r in target_rows])
    exposure = np.where(past_due > 0, past_due, total_bal)
    crit = np.column_stack([recoverable, last_pay, exposure, pay_prob, expected])
    topsis_scores = topsis(crit, [0.15, 0.05, 0.15, 0.30, 0.35], [True, True, True, True, True])

    ranked_actions, ahp_cr = ahp.rank_actions(criteria_matrix)
    action_pref = [a for a, _ in ranked_actions]

    worklist = []
    for i, r in enumerate(target_rows):
        sr = sig_results[i]
        level, reasons = sr["priority_level"], list(sr["reasons"])
        reason_key = feature_rows[i]["_reason_key"]
        hard_risk = sr["redflag"] and reason_key in ("refusal", "absconding", "account_closed", "dispute")
        mob = r.get("months_on_book")
        if mob is None and r.get("customer_tenure_years") is not None:
            mob = float(r["customer_tenure_years"]) * 12
        early_default = mob is not None and float(mob) <= 6

        nba, nba_why = choose_action(
            level, segments[i], int(r.get("current_bucket") or 0), float(pay_prob[i]),
            int(r.get("refusals_6m") or 0), r.get("preferred_channel"), action_pref,
            hard_risk=hard_risk, early_default=early_default,
        )

        worklist.append({
            "account_id": r.get("account_id"),
            "region": r.get("region") or "",
            "segment": segments[i],
            "current_bucket": int(r.get("current_bucket") or 0),
            "days_past_due": int(r.get("days_past_due") or 0) if r.get("days_past_due") is not None else None,
            "prob_promise_to_pay": round(float(pay_prob[i]), 4),
            "prob_actual_payment": round(float(pay_prob[i]), 4),
            "pos": round(float(total_bal[i]), 2),
            "past_due_amount": round(float(past_due[i]), 2),
            "next_installment_amount": round(float(next_inst[i]), 2),
            "recoverable_amount": round(float(recoverable[i]), 2),
            "expected_payment": round(float(expected[i]), 2),
            "priority_score": sr["priority_score"],
            "priority_level": level,
            "confidence": sr["confidence"],
            "topsis_score": round(float(topsis_scores[i]), 4),
            "next_best_action": nba,
            "preferred_channel": r.get("preferred_channel") or "",
            "best_contact_hour": r.get("best_contact_hour"),
            "geo_x": r.get("geo_x"),
            "geo_y": r.get("geo_y"),
            "reasons": reasons,
            "_features": feature_rows[i],
            "audit": {
                "pay_source": pay_source,
                "confidence": sr["confidence"],
                "priority_components": sr["components"],
                "action_from": "flexible risk-priority x compliance playbook",
                "action_rationale": nba_why,
                "human_review_required": level == "High" or nba == "legal_escalation",
            },
        })

    # Rank by flexible risk-priority (primary), TOPSIS closeness as tiebreaker.
    worklist.sort(key=lambda w: (-w["priority_score"], -w["topsis_score"]))
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

    summary["confidence"] = sig_meta.get("confidence")
    summary["pay_source"] = pay_source

    return {
        "model_metrics": trained["metrics"],
        "ahp_consistency_ratio": ahp_cr,
        "ahp_action_ranking": ranked_actions,
        "signal_meta": sig_meta,
        "summary": summary,
        "worklist": worklist,
        "routes": routes,
    }
