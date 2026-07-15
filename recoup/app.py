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
import cgi
import json
import os
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
MAX_ROWS = 4000         # cap rows returned to the browser to keep the DOM sane


def bootstrap(n_synth):
    """Generate synthetic data if needed and fit the model once."""
    global MODEL, TRAIN_ROWS
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


def score_rows(rows, report, collectors, top_field):
    results = MODEL.score(rows, n_collectors=collectors, top_k_field=top_field)
    results["ingest_report"] = report
    # If the upload carried labels, evaluate against them for transparency.
    labeled = [r for r in rows if r.get("label_actual_payment") is not None]
    results["uploaded_labels_present"] = len(labeled) == len(rows) and len(rows) > 0
    return _trim(results)


class Handler(BaseHTTPRequestHandler):
    def _send(self, code, body, ctype="application/json"):
        if isinstance(body, (dict, list)):
            body = json.dumps(body).encode()
        elif isinstance(body, str):
            body = body.encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
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
        else:
            self._send(404, {"error": "not found"})

    def do_POST(self):
        if self.path.split("?")[0] != "/api/score":
            return self._send(404, {"error": "not found"})
        ctype = self.headers.get("Content-Type", "")
        if "multipart/form-data" not in ctype:
            return self._send(400, {"error": "expected a multipart file upload"})
        form = cgi.FieldStorage(
            fp=self.rfile, headers=self.headers,
            environ={"REQUEST_METHOD": "POST", "CONTENT_TYPE": ctype},
        )
        if "file" not in form:
            return self._send(400, {"error": "no file field in upload"})
        item = form["file"]
        raw = item.file.read()
        if not raw:
            return self._send(400, {"error": "uploaded file is empty"})

        collectors = int(form.getvalue("collectors", "3") or 3)
        top_field = int(form.getvalue("top_field", "90") or 90)

        tmp = tempfile.NamedTemporaryFile(mode="wb", suffix=".csv", delete=False)
        try:
            tmp.write(raw)
            tmp.close()
            rows, report = ingest.load_csv(tmp.name)
            if not rows:
                return self._send(400, {"error": "no data rows found in file"})
            results = score_rows(rows, report, collectors, top_field)
            results["source"] = getattr(item, "filename", "upload") or "upload"
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
