# Recoup 💸

**A collections decision copilot — turns predictions into ranked, explainable, compliant actions.**

Recoup is a working prototype of the three-layer decision-support framework from
Lappas & Xanthopoulos, *"Intelligent decision support for debt collection using
predictive learning and multi-criteria optimization,"* Finance Research Open (2026).
For every debtor it produces a promise-to-pay and actual-payment probability, a
human-readable priority with reasons, a next best action bounded by a compliance
playbook, and an optimized field-visit route — all with a per-decision audit trail.

> All numbers in this repo come from a **synthetic portfolio**. No real debtor
> data is used. See [`PRD.md`](PRD.md) for the full product definition.

## What's inside

```
recoup/
├── PRD.md                 product requirements document
├── app.py                 local web app (file-upload UI + scoring API)
├── web/index.html         the app's front-end (upload, worklist, routing, audit)
├── src/
│   ├── generate_data.py   synthetic collections book (labeled)
│   ├── ingest.py          schema-flexible CSV loader (auto column mapping)
│   ├── pipeline.py        the 3-layer orchestrator
│   ├── fuzzy.py           Mamdani fuzzy priority (Layer 3)
│   ├── topsis.py          TOPSIS debtor ranking (Layer 3)
│   ├── ahp.py             AHP next-best-action weighting (Layer 3)
│   ├── routing.py         K-means + 2-opt field routing (Layer 3)
│   ├── run.py             CLI entry point
│   └── build_demo.py      inlines results into the HTML demo
├── demo/
│   └── index.html         self-contained interactive demo
└── output/results.json    latest scored portfolio
```

## The three layers

| Layer | Method | Module |
|-------|--------|--------|
| **1 · Rule extraction** (unsupervised) | K-means behavioral segments: self-cured, lazy-payer, delinquent, defaulter | `pipeline._segment` |
| **2 · Prediction** (supervised) | Random Forest → Promise-to-Pay (P2P) & Actual-Payment (APP) probabilities | `pipeline.train_models` |
| **3 · Optimization** | Fuzzy priority · TOPSIS ranking · AHP action selection · field routing | `fuzzy`, `topsis`, `ahp`, `routing` |

## Run the local app (file upload)

The fastest way to try Recoup on your own data — a local web app with a
drag-and-drop CSV uploader. Nothing leaves your machine.

```bash
pip install -r requirements.txt

cd recoup
python app.py                 # then open http://localhost:8000
# python app.py --port 8080 --n-synth 8000   # options
```

Then in the browser:

1. **Drop a CSV** (any column names) or click **Try sample data**.
2. Recoup maps your columns, scores every account, and shows the ranked
   worklist, field-routing map, model performance, AHP weighting, and
   compliance panel.
3. Click any worklist row to see its reasons and full audit trail.
4. **Download template CSV** gives you a correctly-shaped file to start from.

If your file includes `label_actual_payment` / `label_promise_to_pay` columns,
Recoup evaluates the models on your data and reports the metrics.

## Command line

```bash
cd recoup/src
python run.py                 # generate synthetic data, train, score, write output/results.json
python build_demo.py          # rebuild the self-contained demo/index.html (static artifact)
```

### Score your own data (CLI)

```bash
python run.py --data /path/to/your_accounts.csv --collectors 4 --top-field 120
```

Recoup auto-maps your columns to its canonical fields (it understands common
synonyms like `loan_id`, `dpd`, `overdue`, `emi`, `ptp`, …), imputes anything
missing, and prints exactly what it recognized. The models train on the bundled
synthetic book unless your file carries `label_promise_to_pay` /
`label_actual_payment` columns, in which case those are used and evaluated too.

## Output

`output/results.json` contains:

- `model_metrics` — held-out AUC / accuracy / F1 for P2P and APP
- `summary` — portfolio totals, priority/segment/action breakdowns
- `worklist` — one ranked, explained row per account (priority, reasons, next
  best action, and an `audit` block with the model, fuzzy inputs, and
  human-review flag)
- `routes` — field-collector routes with stops, distance, and expected recovery
- `ingest_report` — how your columns were mapped

## Prototype results (synthetic, 5,000 accounts)

| Model | AUC | Accuracy | F1 |
|-------|-----|----------|----|
| Promise-to-Pay | ~0.94 | ~0.87 | ~0.90 |
| Actual-Payment | ~0.94 | ~0.87 | ~0.80 |

(The source paper reports 0.97–0.98 AUC on real mortgage data.)

## License

See repository [`LICENSE`](../LICENSE).
