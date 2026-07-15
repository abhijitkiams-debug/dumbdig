"""
AHP-based collection-strategy selection (paper Section 4.3.3.4).

Given a manager's pairwise judgements over criteria (Effectiveness, Cost, Time
Efficiency) and how each candidate action scores on those criteria, produce a
ranked "next best action" list -- with a consistency check on the judgements.
"""

import numpy as np

CRITERIA = ["effectiveness", "cost", "time_efficiency"]

# Candidate actions and their intrinsic profile on each criterion, on a 1-9
# scale. Cost/time are framed as benefit ("cost efficiency", "time efficiency"),
# i.e. higher = cheaper / faster, so all criteria are maximized.
ACTIONS = {
    "sms_reminder":     {"effectiveness": 3, "cost": 9, "time_efficiency": 9},
    "automated_call":   {"effectiveness": 5, "cost": 6, "time_efficiency": 7},
    "agent_call":       {"effectiveness": 8, "cost": 3, "time_efficiency": 5},
    "email":            {"effectiveness": 3, "cost": 8, "time_efficiency": 8},
    "letter":           {"effectiveness": 4, "cost": 4, "time_efficiency": 2},
    "settlement_offer": {"effectiveness": 7, "cost": 4, "time_efficiency": 4},
    "legal_escalation": {"effectiveness": 9, "cost": 1, "time_efficiency": 1},
}

# Random Index for consistency ratio (Saaty), by matrix size.
RI = {1: 0.0, 2: 0.0, 3: 0.58, 4: 0.90, 5: 1.12, 6: 1.24, 7: 1.32}


def _priority_vector(A):
    """Principal-eigenvector priorities + consistency ratio for matrix A."""
    A = np.asarray(A, dtype=float)
    n = A.shape[0]
    vals, vecs = np.linalg.eig(A)
    k = int(np.argmax(vals.real))
    w = np.abs(vecs[:, k].real)
    w = w / w.sum()
    lam_max = vals.real[k]
    ci = (lam_max - n) / (n - 1) if n > 1 else 0.0
    cr = ci / RI.get(n, 1.32) if RI.get(n, 1.32) else 0.0
    return w, round(float(cr), 3)


# A default, consistent criteria-comparison matrix (Effectiveness > Cost,
# balanced with Time). Managers can override this.
DEFAULT_CRITERIA_MATRIX = [
    [1, 3, 2],
    [1 / 3, 1, 1 / 2],
    [1 / 2, 2, 1],
]


def rank_actions(criteria_matrix=None, allowed=None):
    """
    Returns (ranked list of (action, score), consistency_ratio).
    `allowed` optionally restricts the action set (e.g. compliance limits).
    """
    A = criteria_matrix or DEFAULT_CRITERIA_MATRIX
    w, cr = _priority_vector(A)

    actions = {k: v for k, v in ACTIONS.items() if (allowed is None or k in allowed)}
    names = list(actions.keys())
    # Score matrix S (actions x criteria), column-normalized to local priorities.
    S = np.array([[actions[a][c] for c in CRITERIA] for a in names], dtype=float)
    S = S / S.sum(axis=0, keepdims=True)
    scores = S @ w  # global priority (v = S w)

    ranked = sorted(zip(names, scores), key=lambda t: -t[1])
    return [(a, round(float(s), 4)) for a, s in ranked], cr


# Priority-level -> recommended action *sequence*, per the paper's Section 5.3.2
# playbook. AHP picks the ordering within the allowed set; this maps the
# fuzzy priority level to which actions are even on the table.
PLAYBOOK = {
    "High":   ["agent_call", "sms_reminder", "email", "letter", "settlement_offer", "legal_escalation"],
    "Medium": ["email", "sms_reminder", "automated_call", "letter", "settlement_offer"],
    "Low":    ["sms_reminder", "email", "automated_call"],
}
