"""
Field-collection routing (paper Section 4.3.3.3): cluster-first, route-second.

K-Means partitions prioritized debtors into geographically compact groups (one
per field collector), then a 2-opt heuristic orders each group into a short
visiting route. Travel cost blends geography with recovery value so agents are
steered toward high-value stops, exactly as in Eq. 24.
"""

import numpy as np
from sklearn.cluster import KMeans


def _route_cost(order, D):
    return sum(D[order[i], order[i + 1]] for i in range(len(order) - 1))


def _two_opt(coords, D):
    """2-opt local search over an open route starting at index 0."""
    n = len(coords)
    if n <= 2:
        return list(range(n))
    order = list(range(n))
    improved = True
    while improved:
        improved = False
        for i in range(1, n - 1):
            for j in range(i + 1, n):
                if j - i == 1:
                    continue
                new = order[:i] + order[i:j][::-1] + order[j:]
                if _route_cost(new, D) + 1e-9 < _route_cost(order, D):
                    order = new
                    improved = True
    return order


def plan_routes(accounts, n_collectors=3, value_weight=0.35):
    """
    accounts: list of dicts with geo_x, geo_y, expected_payment, account_id.
    Returns list of routes; each route is a dict with collector id, ordered
    stops, and total travel distance.
    """
    if not accounts:
        return []
    xy = np.array([[a["geo_x"], a["geo_y"]] for a in accounts], dtype=float)
    val = np.array([a["expected_payment"] for a in accounts], dtype=float)
    # Normalize value into the same scale as coordinates (0..100) and fold it in
    # as an extra clustering dimension so high-value stops group together.
    vnorm = 100 * (val - val.min()) / (val.max() - val.min() + 1e-9)
    feat = np.column_stack([xy, value_weight * vnorm])

    k = min(n_collectors, len(accounts))
    labels = KMeans(n_clusters=k, n_init=10, random_state=42).fit_predict(feat)

    routes = []
    for c in range(k):
        idx = [i for i in range(len(accounts)) if labels[i] == c]
        if not idx:
            continue
        pts = xy[idx]
        # Distance matrix blends euclidean geography with a value-based term
        # (Eq. 24): visiting two similarly high-value stops in sequence is cheap.
        n = len(idx)
        D = np.zeros((n, n))
        for a in range(n):
            for b in range(n):
                geo = np.hypot(pts[a, 0] - pts[b, 0], pts[a, 1] - pts[b, 1])
                vgap = abs(vnorm[idx[a]] - vnorm[idx[b]]) * value_weight
                D[a, b] = geo + vgap
        order = _two_opt(pts, D)
        stops = [accounts[idx[o]] for o in order]
        routes.append({
            "collector": f"Collector-{c + 1}",
            "num_stops": len(stops),
            "total_distance": round(float(_route_cost(order, D)), 2),
            "expected_recovery": round(float(sum(s["expected_payment"] for s in stops)), 2),
            "stops": [
                {"seq": i + 1, "account_id": s["account_id"],
                 "geo_x": s["geo_x"], "geo_y": s["geo_y"],
                 "expected_payment": round(s["expected_payment"], 2)}
                for i, s in enumerate(stops)
            ],
        })
    return routes
