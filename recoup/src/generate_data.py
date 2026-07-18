"""
Synthetic collections dataset generator for Aayudh.

We do NOT use any real debtor data (the source paper's data is confidential).
Instead we simulate a plausible collections book with a hidden behavioral model,
so the downstream classifiers have a real signal to learn. Every field mirrors
the variables described in Lappas & Xanthopoulos (2026), Tables 9-14:
communication history, payment history, monthly loan status, participant
demographics, and current loan status -- collapsed into one modeling table with
engineered, time-windowed features.

Output: data/accounts.csv  (one row per debtor account)
"""

import csv
import math
import os
import random

RNG = random.Random(42)

REGIONS = ["North", "East", "South", "West"]
OCCUPATIONS = ["salaried", "self_employed", "gig", "retired", "unemployed"]
MARITAL = ["single", "married", "divorced", "widowed"]
CHANNELS = ["sms", "call", "email", "letter"]

# Delinquency buckets, exactly as defined in the paper (days past due).
# 0: current/<=30, 1: 31-60, 2: 61-90, 3: 91-120, 4: >120
BUCKET_DPD = {0: (0, 30), 1: (31, 60), 2: (61, 90), 3: (91, 120), 4: (121, 260)}


def _clip(x, lo, hi):
    return max(lo, min(hi, x))


def _make_account(i):
    # ---- Hidden behavioral drivers (never exposed to the model) ------------
    # willingness: intent to engage/pay; capacity: ability to pay.
    willingness = _clip(RNG.betavariate(2, 2), 0.02, 0.98)
    capacity = _clip(RNG.betavariate(2, 2.5), 0.02, 0.98)
    health = 0.55 * willingness + 0.45 * capacity  # overall account health

    # Delinquency bucket is anti-correlated with account health.
    # Healthier accounts concentrate in low buckets.
    bucket_score = _clip(1 - health + RNG.gauss(0, 0.15), 0, 1)
    bucket = min(4, int(bucket_score * 5))
    dpd_lo, dpd_hi = BUCKET_DPD[bucket]
    days_past_due = RNG.randint(dpd_lo, dpd_hi)

    # ---- Loan economics ---------------------------------------------------
    lent_amount = round(RNG.uniform(5_000, 250_000), 2)
    interest = round(RNG.uniform(0.04, 0.19), 4)
    term_months = RNG.choice([60, 120, 180, 240, 300, 360])
    # Outstanding shrinks with how far into the term we are.
    progress = RNG.uniform(0.05, 0.85)
    total_balance = round(lent_amount * (1 - progress) * RNG.uniform(0.9, 1.05), 2)
    monthly_installment = round(
        lent_amount * (interest / 12) / (1 - (1 + interest / 12) ** -term_months), 2
    )
    next_installment_amount = round(monthly_installment * RNG.uniform(0.95, 1.1), 2)
    # Past due grows with bucket.
    past_due_amount = round(next_installment_amount * (bucket + RNG.uniform(0.2, 1.3)), 2)

    # ---- Payment history features (engineered / time-windowed) ------------
    # More willing+capable debtors pay more, more consistently, more recently.
    avg_pay_amount_ever = round(
        monthly_installment * _clip(0.3 + 0.9 * capacity + RNG.gauss(0, 0.1), 0.05, 1.4), 2
    )
    last_pay_amount = round(
        avg_pay_amount_ever * _clip(RNG.gauss(0.5 + willingness, 0.3), 0.0, 1.6), 2
    )
    days_since_last_payment = int(_clip(RNG.expovariate(1 / (25 + bucket * 30)), 1, 400))
    num_payments_12m = int(_clip(RNG.gauss(12 * health, 2), 0, 12))
    pay_ratio_6m = round(_clip(0.2 + 0.75 * capacity + RNG.gauss(0, 0.12), 0, 1.2), 3)
    std_pay_amount = round(avg_pay_amount_ever * RNG.uniform(0.05, 0.5), 2)

    # ---- Communication history features -----------------------------------
    contact_attempts_6m = int(_clip(RNG.gauss(6 + bucket * 2, 3), 0, 40))
    # Right-party-contact and promise rates rise with willingness.
    rpc_rate = round(_clip(0.25 + 0.65 * willingness + RNG.gauss(0, 0.1), 0, 1), 3)
    promises_made_6m = int(_clip(contact_attempts_6m * rpc_rate * willingness * RNG.uniform(0.4, 1.0), 0, 30))
    promises_kept_6m = int(_clip(promises_made_6m * (0.3 + 0.6 * capacity) * RNG.uniform(0.4, 1.0), 0, promises_made_6m))
    refusals_6m = int(_clip(contact_attempts_6m * (1 - willingness) * RNG.uniform(0, 0.5), 0, 20))
    days_since_last_contact = int(_clip(RNG.expovariate(1 / 20), 0, 200))
    preferred_channel = RNG.choices(CHANNELS, weights=[3, 3, 2, 1])[0]
    best_contact_hour = RNG.choice([9, 10, 11, 14, 15, 16, 17, 18, 19])

    # ---- Demographics -----------------------------------------------------
    age = RNG.randint(21, 78)
    occupation = RNG.choices(OCCUPATIONS, weights=[4, 3, 2, 1.5, 1])[0]
    marital_status = RNG.choice(MARITAL)
    customer_tenure_years = round(RNG.uniform(0.5, 22), 1)
    num_co_borrowers = RNG.choices([0, 1, 2], weights=[6, 3, 1])[0]

    # ---- Geography (for field routing) ------------------------------------
    region = REGIONS[bucket % 4] if RNG.random() < 0.15 else RNG.choice(REGIONS)
    # cluster regions into rough quadrants of a 100x100 grid
    base = {"North": (25, 75), "East": (75, 75), "South": (75, 25), "West": (25, 25)}[region]
    geo_x = round(_clip(RNG.gauss(base[0], 12), 0, 100), 2)
    geo_y = round(_clip(RNG.gauss(base[1], 12), 0, 100), 2)

    # ---- Ground-truth labels (latent -> observed) -------------------------
    # Promise-to-pay: driven by willingness + recent right-party contact.
    p2p_logit = -1.4 + 3.2 * willingness + 1.1 * rpc_rate - 0.15 * bucket + RNG.gauss(0, 0.4)
    p2p = 1 if 1 / (1 + math.exp(-p2p_logit)) > 0.5 else 0
    # Actual payment: needs both willingness AND capacity; harder in high buckets.
    app_logit = -1.9 + 2.0 * willingness + 2.6 * capacity - 0.35 * bucket + RNG.gauss(0, 0.4)
    app = 1 if 1 / (1 + math.exp(-app_logit)) > 0.5 else 0

    return {
        "account_id": f"ACC-{i:05d}",
        "region": region,
        "geo_x": geo_x,
        "geo_y": geo_y,
        # demographics
        "age": age,
        "occupation": occupation,
        "marital_status": marital_status,
        "customer_tenure_years": customer_tenure_years,
        "num_co_borrowers": num_co_borrowers,
        # loan economics
        "lent_amount": lent_amount,
        "interest": interest,
        "term_months": term_months,
        "total_balance": total_balance,
        "next_installment_amount": next_installment_amount,
        "past_due_amount": past_due_amount,
        "current_bucket": bucket,
        "days_past_due": days_past_due,
        # payment history
        "avg_pay_amount_ever": avg_pay_amount_ever,
        "last_pay_amount": last_pay_amount,
        "days_since_last_payment": days_since_last_payment,
        "num_payments_12m": num_payments_12m,
        "pay_ratio_6m": pay_ratio_6m,
        "std_pay_amount": std_pay_amount,
        # communication history
        "contact_attempts_6m": contact_attempts_6m,
        "rpc_rate": rpc_rate,
        "promises_made_6m": promises_made_6m,
        "promises_kept_6m": promises_kept_6m,
        "refusals_6m": refusals_6m,
        "days_since_last_contact": days_since_last_contact,
        "preferred_channel": preferred_channel,
        "best_contact_hour": best_contact_hour,
        # labels
        "label_promise_to_pay": p2p,
        "label_actual_payment": app,
    }


def generate(n=5000, out_path=None):
    rows = [_make_account(i) for i in range(n)]
    if out_path is None:
        out_path = os.path.join(os.path.dirname(__file__), "..", "data", "accounts.csv")
    out_path = os.path.abspath(out_path)
    os.makedirs(os.path.dirname(out_path), exist_ok=True)  # dir may not exist on a fresh clone
    with open(out_path, "w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)
    return out_path, rows


if __name__ == "__main__":
    path, rows = generate()
    p2p = sum(r["label_promise_to_pay"] for r in rows)
    app = sum(r["label_actual_payment"] for r in rows)
    print(f"Wrote {len(rows)} accounts -> {path}")
    print(f"  promise-to-pay positives: {p2p} ({p2p/len(rows):.1%})")
    print(f"  actual-payment positives: {app} ({app/len(rows):.1%})")
