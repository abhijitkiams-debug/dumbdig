"""
Recoup — local web app.

A dependency-light local server (Python stdlib + numpy/scikit-learn) that:
  • fits the P2P/APP models + segmenter ONCE at startup on the synthetic book,
  • serves an upload UI at http://localhost:8000,
  • scores an uploaded CSV of accounts and returns the full explainable worklist,
  • can also score the bundled synthetic sample on demand.

Run:
    cd recoup && python app.py            # then open http://localhost:8000
    python app.py --port 8080 --n-synth 8000
"""

import argparse
import json
import os
import re
import sys
import tempfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "src"))

import generate_data       # noqa: E402
import ingest              # noqa: E402
import pipeline            # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(HERE, "data", "accounts.csv")
WEB = os.path.join(HERE, "web", "index.html")

MODEL = None            # fitted pipeline.Recoup
TRAIN_ROWS = None       # synthetic labeled book (also used as the sample)
LAST_WORKLIST = None    # full scored worklist from the most recent run (for CSV export)
MAX_ROWS = 4000         # cap rows returned to the browser to keep the DOM sane


def bootstrap(n_synth):
    """Generate synthetic data if needed and fit the model once."""
    global MODEL, TRAIN_ROWS
    os.makedirs(os.path.dirname(DATA), exist_ok=True)  # fresh clone has no data/ dir
    if not os.path.exists(DATA):
        print(f"Generating {n_synth} synthetic accounts …")
        generate_data.generate(n_synth, DATA)
    TRAIN_ROWS, _ = ingest.load_csv(DATA)
    print(f"Fitting models on {len(TRAIN_ROWS)} accounts …")
    MODEL = pipeline.Recoup().fit(TRAIN_ROWS)
    for lbl, m in MODEL.metrics.items():
        print(f"  {lbl:26s} AUC={m['auc']}  ACC={m['accuracy']}  F1={m['f1']}")
    print("Model ready.\n")


def _trim(results):
    """Cap worklist size for the browser; keep the summary computed on all rows."""
    wl = results["worklist"]
    if len(wl) > MAX_ROWS:
        results["worklist"] = wl[:MAX_ROWS]
        results["worklist_truncated"] = {"shown": MAX_ROWS, "total": len(wl)}
    return results


# Curated catalog of fields a user can map to ("what to include"), grouped and
# described for the mapping UI. Only these are offered; the loader still accepts
# the full synonym set for auto-detection.
MAP_FIELDS = [
    {"key": "account_id", "label": "Account / Agreement ID", "group": "Identity", "desc": "Unique loan or agreement number", "req": True},
    {"key": "region", "label": "Region / Branch", "group": "Identity", "desc": "Branch, area or state — used to group field visits"},
    {"key": "geo_x", "label": "Longitude / X", "group": "Identity", "desc": "Coordinate for field-visit routing"},
    {"key": "geo_y", "label": "Latitude / Y", "group": "Identity", "desc": "Coordinate for field-visit routing"},

    {"key": "total_balance", "label": "POS / Outstanding", "group": "Exposure", "desc": "Principal outstanding — the amount to recover", "req": True},
    {"key": "past_due_amount", "label": "Overdue / Arrears", "group": "Exposure", "desc": "Amount currently overdue"},
    {"key": "next_installment_amount", "label": "EMI / Installment", "group": "Exposure", "desc": "Next installment amount due"},
    {"key": "days_past_due", "label": "DPD", "group": "Exposure", "desc": "Days past due"},
    {"key": "current_bucket", "label": "Bucket", "group": "Exposure", "desc": "Delinquency bucket (0-4)"},

    {"key": "last_pay_amount", "label": "Last paid amount", "group": "Payment history", "desc": "Amount of the last payment"},
    {"key": "last_payment_date", "label": "Last paid date (LMPD)", "group": "Payment history", "desc": "Date of the last payment"},
    {"key": "pay_ratio_6m", "label": "Payment ratio (6m)", "group": "Payment history", "desc": "Share of dues paid recently (0-1)"},
    {"key": "num_payments_12m", "label": "Payments (12m)", "group": "Payment history", "desc": "Number of payments in the last year"},

    {"key": "rpc_rate", "label": "Right-party contact rate", "group": "Contact & mandate", "desc": "How often the right person is reached (0-1)"},
    {"key": "promises_kept_6m", "label": "Promises kept (6m)", "group": "Contact & mandate", "desc": "Kept promises to pay"},
    {"key": "refusals_6m", "label": "Refusals (6m)", "group": "Contact & mandate", "desc": "Refusals or disputes"},
    {"key": "preferred_channel", "label": "Preferred channel", "group": "Contact & mandate", "desc": "sms / call / email / letter"},
    {"key": "mandate_status", "label": "Mandate status", "group": "Contact & mandate", "desc": "NACH mandate active / cancelled"},
    {"key": "presentation_status", "label": "Presentation status", "group": "Contact & mandate", "desc": "EMI presented / bounced / returned"},

    {"key": "months_on_book", "label": "Months on book (MOB)", "group": "Profile", "desc": "Account age in months"},
    {"key": "age", "label": "Age", "group": "Profile", "desc": "Borrower age"},

    {"key": "label_actual_payment", "label": "Did pay (label)", "group": "Labels (optional)", "desc": "1/0 outcome, to evaluate model accuracy"},
    {"key": "label_promise_to_pay", "label": "Promised to pay (label)", "group": "Labels (optional)", "desc": "1/0 promise, to evaluate model accuracy"},
]


def parse_multipart(headers, body):
    """
    Minimal multipart/form-data parser (stdlib only), so this runs on Python
    3.13+ where the old `cgi` module was removed. Returns (text_fields, files)
    where files maps field name -> {"filename", "content" (bytes)}.
    """
    ctype = headers.get("Content-Type", "")
    m = re.search(r"boundary=([^;]+)", ctype)
    if not m:
        return {}, {}
    delim = b"--" + m.group(1).strip().strip('"').encode()
    fields, files = {}, {}
    for part in body.split(delim):
        part = part.strip(b"\r\n")
        if not part or part == b"--" or b"\r\n\r\n" not in part:
            continue
        raw_headers, content = part.split(b"\r\n\r\n", 1)
        hdrs = raw_headers.decode("utf-8", "replace")
        disp = next((ln for ln in hdrs.split("\r\n")
                     if ln.lower().startswith("content-disposition")), "")
        name_m = re.search(r'name="([^"]*)"', disp)
        if not name_m:
            continue
        name = name_m.group(1)
        fname_m = re.search(r'filename="([^"]*)"', disp)
        if fname_m:
            files[name] = {"filename": fname_m.group(1), "content": content}
        else:
            fields[name] = content.decode("utf-8", "replace").strip()
    return fields, files


def score_rows(rows, report, collectors, top_field):
    global LAST_WORKLIST
    results = MODEL.score(rows, n_collectors=collectors, top_k_field=top_field)
    results["ingest_report"] = report
    # If the upload carried labels, evaluate against them for transparency.
    labeled = [r for r in rows if r.get("label_actual_payment") is not None]
    results["uploaded_labels_present"] = len(labeled) == len(rows) and len(rows) > 0
    LAST_WORKLIST = list(results["worklist"])  # keep the FULL list for CSV export
    return _trim(results)


# Columns exported to CSV, in order.
EXPORT_COLUMNS = [
    "rank", "account_id", "region", "segment", "current_bucket",
    "priority_level", "priority_score", "prob_promise_to_pay", "prob_actual_payment",
    "pos", "past_due_amount", "recoverable_amount", "expected_payment", "topsis_score",
    "next_best_action", "preferred_channel", "best_contact_hour",
    "human_review_required", "reason",
]


def worklist_to_csv(worklist):
    import csv
    import io
    buf = io.StringIO()
    w = csv.writer(buf)
    w.writerow(EXPORT_COLUMNS)
    for row in worklist:
        w.writerow([
            row.get("rank"), row.get("account_id"), row.get("region"), row.get("segment"),
            row.get("current_bucket"), row.get("priority_level"), row.get("priority_score"),
            row.get("prob_promise_to_pay"), row.get("prob_actual_payment"),
            row.get("pos"), row.get("past_due_amount"), row.get("recoverable_amount"),
            row.get("expected_payment"), row.get("topsis_score"), row.get("next_best_action"),
            row.get("preferred_channel"), row.get("best_contact_hour"),
            row.get("audit", {}).get("human_review_required"),
            "; ".join(row.get("reasons", []) + [row.get("audit", {}).get("action_rationale", "")]).strip("; "),
        ])
    return buf.getvalue()


class Handler(BaseHTTPRequestHandler):
    def _send(self, code, body, ctype="application/json"):
        if isinstance(body, (dict, list)):
            body = json.dumps(body).encode()
        elif isinstance(body, str):
            body = body.encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        # Never let the browser serve a stale UI after the code is updated.
        self.send_header("Cache-Control", "no-store, must-revalidate")
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *a):  # quieter console
        pass

    def do_GET(self):
        path = self.path.split("?")[0]
        if path in ("/", "/index.html"):
            with open(WEB, "rb") as f:
                self._send(200, f.read().decode(), "text/html; charset=utf-8")
        elif path == "/api/sample":
            results = score_rows(list(TRAIN_ROWS), {"note": "bundled synthetic sample"}, 3, 90)
            results["source"] = "synthetic sample"
            self._send(200, results)
        elif path == "/api/sample-csv":
            # Hand back a small, correctly-shaped CSV users can edit and re-upload.
            with open(DATA) as f:
                head = "".join([next(f) for _ in range(26)])
            self.send_response(200)
            self.send_header("Content-Type", "text/csv")
            self.send_header("Content-Disposition", "attachment; filename=recoup_sample.csv")
            self.send_header("Content-Length", str(len(head.encode())))
            self.end_headers()
            self.wfile.write(head.encode())
        elif path == "/api/export.csv":
            if not LAST_WORKLIST:
                return self._send(400, {"error": "nothing to export yet; score a file first"})
            body = worklist_to_csv(LAST_WORKLIST).encode("utf-8-sig")  # BOM so Excel opens it clean
            self.send_response(200)
            self.send_header("Content-Type", "text/csv")
            self.send_header("Content-Disposition", "attachment; filename=recoup_worklist.csv")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        else:
            self._send(404, {"error": "not found"})

    def do_POST(self):
        path = self.path.split("?")[0]
        if path not in ("/api/score", "/api/inspect"):
            return self._send(404, {"error": "not found"})
        ctype = self.headers.get("Content-Type", "")
        if "multipart/form-data" not in ctype:
            return self._send(400, {"error": "expected a multipart file upload"})
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length)
        fields, files = parse_multipart(self.headers, body)
        if "file" not in files or not files["file"]["content"]:
            return self._send(400, {"error": "no file uploaded"})
        raw = files["file"]["content"]
        filename = files["file"].get("filename") or "upload"

        tmp = tempfile.NamedTemporaryFile(mode="wb", suffix=".dat", delete=False)
        try:
            tmp.write(raw)
            tmp.close()

            if path == "/api/inspect":
                headers, fmt = ingest.read_headers(tmp.name)
                if not headers:
                    return self._send(400, {"error": "could not read a header row from the file"})
                return self._send(200, {
                    "source_columns": headers,
                    "format": fmt,
                    "suggested": ingest.suggest_mapping(headers),
                    "fields": MAP_FIELDS,
                    "filename": filename,
                })

            # /api/score
            mapping = None
            if fields.get("mapping"):
                try:
                    mapping = json.loads(fields["mapping"])
                except (ValueError, TypeError):
                    mapping = None
            collectors = int(fields.get("collectors", "3") or 3)
            top_field = int(fields.get("top_field", "90") or 90)
            rows, report = ingest.load_csv(tmp.name, mapping=mapping)
            if not rows:
                return self._send(400, {"error": "no data rows found in file"})
            results = score_rows(rows, report, collectors, top_field)
            results["source"] = filename
            self._send(200, results)
        except Exception as e:  # surface parsing/scoring errors to the UI
            self._send(400, {"error": f"could not process file: {e}"})
        finally:
            os.unlink(tmp.name)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8000)
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--n-synth", type=int, default=5000)
    args = ap.parse_args()

    bootstrap(args.n_synth)
    srv = ThreadingHTTPServer((args.host, args.port), Handler)
    url = f"http://{args.host}:{args.port}"
    print(f"Recoup is running at {url}  (Ctrl-C to stop)")
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        print("\nStopping.")
        srv.shutdown()


if __name__ == "__main__":
    main()
