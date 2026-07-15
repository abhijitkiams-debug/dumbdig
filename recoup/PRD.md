# Recoup — Product Requirements Document

**A collections decision copilot that turns predictions into ranked, explainable, compliant actions.**

Status: prototype (v0.1) · Owner: TBD · Last updated: 2026-07-15

---

## 1. Summary

Collections teams are drowning in accounts and starved of good decisions. They
have risk scores but not *actions*; they have actions but not *reasons*; and
regulators increasingly demand reasons. Recoup is a decision-support layer that
sits on top of a lender's or agency's account data and, for every debtor,
produces:

1. a **promise-to-pay** and **actual-payment** probability,
2. a human-readable **priority** (Low / Medium / High) with a plain-English why,
3. a **next best action** chosen inside a compliance playbook, and
4. an optimized **field-visit route** for the accounts worth visiting in person.

It is a faithful, buildable implementation of the three-layer framework in
Lappas & Xanthopoulos, *"Intelligent decision support for debt collection using
predictive learning and multi-criteria optimization,"* Finance Research Open
(2026) — extended into a product with a working pipeline and UI.

## 2. Problem & opportunity

- **Fragmented tooling.** The academic and vendor landscape treats prediction,
  segmentation, and optimization as separate products. Teams stitch them by hand.
- **Black-box scores.** Most collections scoring outputs a number with no reason,
  which collectors distrust and regulators reject.
- **Rising compliance pressure.** GDPR, the EU AI Act, and fair-lending regimes
  require interpretable, auditable, human-overseeable automated decisions about
  individuals — exactly what opaque scoring cannot provide.
- **In-person collection is still huge** in many markets, and routing is done on
  intuition, not recoverable value.

The opportunity is a single system that connects a prediction to a *defensible
action*, with explainability and human-in-the-loop controls as first-class
features rather than bolt-ons.

## 3. Target users

| User | What they need from Recoup |
|------|----------------------------|
| **Collections agent** | A ranked daily worklist and a clear next action per account, with a reason they can say out loud. |
| **Collections manager** | Portfolio-level priority and recovery view; control over strategy weighting (AHP); staffing/routing for field teams. |
| **Risk / compliance officer** | An audit trail per decision, guarantees on which actions are automated vs. human-reviewed, interpretability for regulators. |
| **Field collector** | An optimized route weighted by recoverable value, not just distance. |

## 4. Scope

### In scope (v1)
The three product pillars the copilot unifies:

- **P1 — Next-Best-Action copilot (core).** Ingest → score (P2P/APP) → fuzzy
  priority → TOPSIS rank → AHP action selection → explainable worklist.
- **P2 — Field routing module.** Cluster-first, route-second field-visit planning
  weighted by expected recovery.
- **P3 — Explainability & compliance layer.** Per-decision audit trail, playbook
  bounds on actions, human-review flags, interpretable priority reasoning.

### Out of scope (v1)
- Two-way integrations that *execute* actions (dialer, SMS/email gateways) — v1
  recommends; execution is a later connector.
- Real-time streaming scoring (v1 is batch).
- LLM analysis of free-text notes and reinforcement-learning call timing (see
  Roadmap — these are the paper's own "future work").

## 5. How it works (architecture)

Three layers, mirroring the source framework:

```
        ┌─────────────────────────────────────────────────────────┐
  data  │  ingest.py — schema-flexible column mapping + imputation │
  ─────▶│                                                          │
        └───────────────────────────┬─────────────────────────────┘
                                     ▼
   Layer 1  Rule Extraction   K-means behavioral segments
            (unsupervised)    self-cured · lazy-payer · delinquent · defaulter
                                     ▼
   Layer 2  Prediction        Random Forest classifiers
            (supervised)      P2P (promise-to-pay) · APP (actual-payment)
                                     ▼
   Layer 3  Optimization      Mamdani fuzzy → Debt Priority Level (+ reasons)
                              TOPSIS       → portfolio ranking
                              AHP          → next-best-action weighting
                              K-means+2opt → field routing
                                     ▼
        ┌─────────────────────────────────────────────────────────┐
        │  explainable worklist · routes · audit trail (JSON/UI)   │
        └─────────────────────────────────────────────────────────┘
```

Every worklist row carries the model, the inputs, and the rule that produced its
priority and action — that traceability *is* the compliance layer.

## 6. Functional requirements

- **FR-1 Ingestion.** Accept arbitrary CSV schemas; auto-map columns via
  synonyms; impute missing fields and report exactly what was recognized.
- **FR-2 Segmentation.** Assign each account a behavioral segment.
- **FR-3 Scoring.** Produce calibrated P2P and APP probabilities; report held-out
  AUC / accuracy / F1.
- **FR-4 Priority.** Produce a 0–100 priority score, a Low/Med/High level, and at
  least one plain-English reason per account.
- **FR-5 Ranking.** Rank the portfolio by TOPSIS closeness across financial and
  behavioral criteria.
- **FR-6 Action.** Recommend a next best action bounded by a per-tier compliance
  playbook, personalized to the debtor's reachable channel and contact history.
- **FR-7 Routing.** Given field-eligible accounts and a collector count, produce
  compact routes weighted by expected recovery.
- **FR-8 Audit.** Emit a per-decision audit record and a human-review flag for
  high-priority accounts and any legal escalation.

## 7. Non-functional requirements

- **Interpretability first** — no action without a reason.
- **Human-in-the-loop** — legal escalation and high-priority actions never
  auto-fire.
- **Portability** — pure Python + scikit-learn; runs on a laptop (the reference
  paper ran on 8 GB RAM).
- **Data minimization** — no real PII required to evaluate; ships with a synthetic
  book.

## 8. Success metrics

- **Recovery lift** — incremental $ recovered vs. the team's current worklist
  ordering (target: measurable uplift in an A/B against business-as-usual).
- **Cost efficiency** — recovery per contact / per field mile.
- **Adoption** — % of recommended actions accepted by agents.
- **Model quality** — held-out AUC ≥ 0.90 on both P2P and APP (prototype hits
  ≈ 0.94 on synthetic data; the paper reports 0.97–0.98 on real data).
- **Auditability** — 100% of decisions carry a complete trace.

## 9. Roadmap

| Phase | Deliverable |
|-------|-------------|
| **v0.1 (now)** | Prototype: full pipeline on synthetic data + interactive demo + this PRD. |
| **v0.2** | Ingest a customer's real sample export; column-mapping UI; calibration + fairness report. |
| **v1.0** | Hosted worklist app, per-manager AHP configuration, role-based views, exportable audit logs. |
| **v1.x** | Action connectors (dialer/SMS/email), outcome feedback loop, champion/challenger. |
| **v2.0** | Paper's "future work": LLM on free-text collector notes, reinforcement-learning call timing, DBSCAN/fuzzy segmentation. |

## 10. Risks & mitigations

- **Bias / fairness.** Models trained on historical collections may replicate past
  bias. → fairness-aware evaluation, protected-attribute audits, human oversight.
- **Regulatory variance across jurisdictions.** → playbook and constraints are
  configurable boundary conditions, not hard-coded.
- **Data quality on ingestion.** → explicit mapping report; never silently
  fabricate; impute transparently.
- **Over-trust in scores.** → priority reasons and audit trail surfaced by
  default; high-stakes actions gated on human review.

## 11. Open questions

- What is the design partner's real export schema, and which fields are reliably
  populated? (Being validated now with sample uploads.)
- Which action channels can we actually execute against in their stack?
- What fairness constraints and protected attributes apply in their jurisdiction?
