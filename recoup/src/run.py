"""
Aayudh CLI.

Usage:
  python run.py                        # train + score on synthetic data
  python run.py --data path/to.csv     # score YOUR uploaded data
  python run.py --data your.csv --collectors 4 --top-field 120

If the uploaded file has label columns, they're used for evaluation too;
otherwise the models train on the bundled synthetic book and score your rows.
"""

import argparse
import json
import os

import generate_data
import ingest
import pipeline

HERE = os.path.dirname(__file__)
DATA = os.path.abspath(os.path.join(HERE, "..", "data"))
OUT = os.path.abspath(os.path.join(HERE, "..", "output"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", help="CSV of accounts to score (any schema; auto-mapped)")
    ap.add_argument("--collectors", type=int, default=3)
    ap.add_argument("--top-field", type=int, default=90, help="max accounts to route for field visits")
    ap.add_argument("--n-synth", type=int, default=5000)
    args = ap.parse_args()

    os.makedirs(OUT, exist_ok=True)

    # Training book: always the labeled synthetic data (mirrors the paper schema).
    synth_path = os.path.join(DATA, "accounts.csv")
    if not os.path.exists(synth_path):
        generate_data.generate(args.n_synth, synth_path)
    train_rows, train_report = ingest.load_csv(synth_path)

    if args.data:
        target_rows, report = ingest.load_csv(os.path.abspath(args.data))
        print(f"Ingested {report['row_count']} rows from {args.data}")
        print(f"  recognized fields : {len(report['recognized_fields'])}")
        print(f"  unmapped columns  : {report['unmapped_source_columns'] or 'none'}")
        print(f"  missing fields    : {len(report['missing_canonical_fields'])} (imputed)")
    else:
        target_rows, report = train_rows, train_report
        print(f"No --data given; scoring the synthetic book ({len(target_rows)} rows).")

    results = pipeline.score_portfolio(
        train_rows, target_rows,
        n_collectors=args.collectors, top_k_field=args.top_field,
    )
    results["ingest_report"] = report

    out_path = os.path.join(OUT, "results.json")
    with open(out_path, "w") as f:
        json.dump(results, f, indent=2)

    m = results["model_metrics"]
    s = results["summary"]
    print("\n=== Model performance (held-out) ===")
    for label, met in m.items():
        print(f"  {label:26s} AUC={met['auc']}  ACC={met['accuracy']}  F1={met['f1']}")
    print("\n=== Portfolio ===")
    print(f"  accounts                : {s['accounts']}")
    print(f"  total past due          : ${s['total_past_due']:,.2f}")
    print(f"  total expected recovery : ${s['total_expected_recovery']:,.2f}")
    print(f"  priority breakdown      : {s['priority_breakdown']}")
    print(f"  segment breakdown       : {s['segment_breakdown']}")
    print(f"  top actions             : {list(s['action_breakdown'].items())[:4]}")
    print(f"  field visits planned    : {s['field_visits_planned']} across {args.collectors} collectors")
    print(f"\nWrote {out_path}")


if __name__ == "__main__":
    main()
