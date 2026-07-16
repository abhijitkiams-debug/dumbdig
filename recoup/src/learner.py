"""
Continuous learning for Recoup.

Each learning cycle replays everything accumulated in persistent memory
(outcomes + human-in-the-loop feedback) and produces, per lender:
  - an updated knowledge base (reason/stage severities recalibrated to real pay
    rates, action effectiveness, and human-nudged signal weights), and
  - an incremental payment-likelihood model over the flexible signal space.

Replaying from defaults each cycle keeps it deterministic (no double counting)
while still growing smarter as more data arrives -- the model keeps learning.
Uses scikit-learn's incremental SGDClassifier (already a dependency); no new
packages, safe on Python 3.14.
"""

import numpy as np

import knowledge
import memory
import signals

FEATURE_ORDER = [s["name"] for s in signals.SIGNALS]
_LEVEL_RANK = {"Low": 0, "Medium": 1, "High": 2}


def _vec(features):
    return [float(features.get(name, 0.5)) for name in FEATURE_ORDER]


def learn(lender):
    """Rebuild KB + incremental model for a lender from all stored feedback/outcomes."""
    kb = knowledge.KnowledgeBase()  # start from defaults, replay evidence
    outcomes = memory.get_outcomes(lender)
    feedback = memory.get_feedback(lender)
    summary = {"lender": lender, "outcomes": len(outcomes), "feedback": len(feedback),
               "trained_on": 0, "model": None, "kb_changes": {}}

    # ---- outcomes -> KB recalibration + incremental model ------------------
    aids = list({o["account_id"] for o in outcomes})
    feats = memory.latest_features(lender, aids) if aids else {}
    X, y, acts = [], [], []
    for o in outcomes:
        f = feats.get(o["account_id"])
        paid = int(bool(o["paid"]))
        if f:
            comp = f["features"]
            kb.observe_reason_outcome(comp.get("_reason_key"), paid)
            kb.observe_stage_outcome(comp.get("_stage_key"), paid)
            X.append(_vec(comp))
            y.append(paid)
        act = o.get("action_taken") or (f["action"] if f else None)
        if act:
            kb.observe_action_outcome(act, paid)

    model = None
    if X and len(set(y)) >= 2:
        from sklearn.linear_model import SGDClassifier
        model = SGDClassifier(loss="log_loss", random_state=42)
        Xa, ya = np.array(X), np.array(y)
        model.partial_fit(Xa, ya, classes=[0, 1])
        # a couple more passes for stability on small data
        for _ in range(4):
            model.partial_fit(Xa, ya)
        preds = model.predict(Xa)
        summary["trained_on"] = len(X)
        summary["model"] = {"train_acc": round(float((preds == ya).mean()), 3),
                            "pay_rate": round(float(ya.mean()), 3), "n": len(X)}

    # ---- human feedback -> KB signal-weight nudges -------------------------
    for fb in feedback:
        if fb["kind"] == "override" and fb["corrected_level"] and fb["predicted_level"]:
            direction = _LEVEL_RANK.get(fb["corrected_level"], 1) - _LEVEL_RANK.get(fb["predicted_level"], 1)
            if direction == 0:
                continue
            f = feats.get(fb["account_id"]) or memory.latest_features(lender, [fb["account_id"]]).get(fb["account_id"])
            if not f:
                continue
            comp = f["features"]
            top = sorted(((v, k) for k, v in comp.items() if k in FEATURE_ORDER), reverse=True)[:2]
            factor = 1.12 if direction > 0 else 0.9
            for _, name in top:
                kb.nudge_signal_weight(name, factor)
        elif fb["kind"] == "correct_kb" and fb["note"] and "=" in fb["note"]:
            # note format "reason_key=severity", e.g. "insufficient_funds=0.3"
            try:
                key, val = fb["note"].split("=", 1)
                kb.reason_learned[key.strip()] = {"sev": float(val), "paid": 0, "total": 0}
            except ValueError:
                pass

    memory.save_kb(lender, kb.to_dict())
    if model is not None:
        memory.save_model(lender, model, {"features": FEATURE_ORDER})
    summary["kb_changes"] = {"reasons_recalibrated": len(kb.reason_learned),
                             "signal_weights": kb.signal_weights,
                             "actions_tracked": len(kb.action_stats)}
    return summary


def load_kb(lender):
    """KB for scoring: stored (learned) state if present, else defaults."""
    return knowledge.KnowledgeBase(memory.load_kb(lender))


def pay_likelihood(lender, feature_rows):
    """Model-based P(pay) per account if a trained model exists, else None."""
    model, meta = memory.load_model(lender)
    if model is None:
        return None
    order = (meta or {}).get("features", FEATURE_ORDER)
    X = np.array([[float(f.get(name, 0.5)) for name in order] for f in feature_rows])
    try:
        return model.predict_proba(X)[:, 1]
    except Exception:
        return None
