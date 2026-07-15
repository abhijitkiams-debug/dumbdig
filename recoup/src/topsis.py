"""
TOPSIS debtor prioritization (paper Section 4.3.3.1).

Ranks debtors by relative closeness to an ideal recovery profile across several
benefit/cost criteria. Pure NumPy.
"""

import numpy as np


def topsis(matrix, weights, benefit_mask):
    """
    matrix        : (m accounts x n criteria) raw values
    weights       : (n,) non-negative, will be normalized to sum 1
    benefit_mask  : (n,) bool -- True if higher is better (benefit), else cost
    Returns closeness scores C* in [0,1], shape (m,).
    """
    X = np.asarray(matrix, dtype=float)
    w = np.asarray(weights, dtype=float)
    w = w / w.sum()
    benefit_mask = np.asarray(benefit_mask, dtype=bool)

    # Vector normalization (Eq. 13). Guard against all-zero columns.
    norm = np.sqrt((X ** 2).sum(axis=0))
    norm[norm == 0] = 1.0
    R = X / norm

    V = R * w  # weighted normalized (Eq. 14)

    # Ideal / anti-ideal (Eq. 15-16).
    ideal = np.where(benefit_mask, V.max(axis=0), V.min(axis=0))
    anti = np.where(benefit_mask, V.min(axis=0), V.max(axis=0))

    s_ideal = np.sqrt(((V - ideal) ** 2).sum(axis=1))
    s_anti = np.sqrt(((V - anti) ** 2).sum(axis=1))

    denom = s_ideal + s_anti
    denom[denom == 0] = 1.0
    return s_anti / denom  # C* (Eq. 18)
