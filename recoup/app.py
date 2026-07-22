"""
Aayudh — local web app.

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
from urllib.parse import parse_qs, urlparse

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "src"))

import generate_data       # noqa: E402
import ingest              # noqa: E402
import learner             # noqa: E402
import memory              # noqa: E402
import pipeline            # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(HERE, "data", "accounts.csv")
WEB = os.path.join(HERE, "web", "index.html")

MODEL = None            # fitted pipeline.Aayudh
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
    memory.init()  # persistent store for scored/feedback/outcomes/KB/model
    print(f"Fitting models on {len(TRAIN_ROWS)} accounts …")
    MODEL = pipeline.Aayudh().fit(TRAIN_ROWS)
    for lbl, m in MODEL.metrics.items():
        print(f"  {lbl:26s} AUC={m['auc']}  ACC={m['accuracy']}  F1={m['f1']}")
    print("Model ready.\n")


def _trim(results):
    """
    Cap worklist size for the browser while keeping every priority level
    represented (stratified), so filtering High/Medium/Low still shows rows.
    Summary counts are computed on ALL rows and left untouched.
    """
    wl = results["worklist"]
    total = len(wl)
    if total <= MAX_ROWS:
        return results
    buckets = {"High": [], "Medium": [], "Low": []}
    for w in wl:
        buckets.get(w["priority_level"], buckets["Low"]).append(w)
    kept = []
    for lvl, rows in buckets.items():
        share = max(1, round(MAX_ROWS * len(rows) / total)) if rows else 0
        kept.extend(rows[:share])  # rows are already ranked
    kept.sort(key=lambda w: w["rank"])
    results["worklist"] = kept[:MAX_ROWS]
    results["worklist_truncated"] = {"shown": len(results["worklist"]), "total": total}
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
    {"key": "bounce_reason", "label": "Failure / bounce reason", "group": "Contact & mandate", "desc": "Reason for bounce / non-payment"},
    {"key": "field_feedback", "label": "Refusal / field feedback", "group": "Contact & mandate", "desc": "Disposition or field-executive remarks (refused, declined, absconding…)"},
    {"key": "paid_by", "label": "Calling / collection stage", "group": "Contact & mandate", "desc": "Which calling or collection stage is handling the account"},
    {"key": "whatsapp_consent", "label": "WhatsApp consent", "group": "Contact & mandate", "desc": "Opt-in / opt-out for WhatsApp reminders"},

    {"key": "stab_status", "label": "Stability / roll (Stab Frwd)", "group": "Momentum & promise", "desc": "\"Stab forward\" = missed last month & rolling worse; \"Stab\" = held/paid"},
    {"key": "pos_paid_pct", "label": "POS paid %", "group": "Momentum & promise", "desc": "Share of principal outstanding already paid down (higher = better)"},
    {"key": "ptp_date", "label": "PTP date", "group": "Momentum & promise", "desc": "Promised-to-pay date — plan is aligned to honour it"},
    {"key": "ptp_amount", "label": "PTP amount", "group": "Momentum & promise", "desc": "Promised-to-pay amount"},
    {"key": "easy_cure_flag", "label": "Easy-cure flag", "group": "Momentum & promise", "desc": "Likely to self-cure with a light touch"},
    {"key": "wa_read_count", "label": "WhatsApp reads (Total Read)", "group": "Momentum & promise", "desc": "Times the borrower opened WhatsApp messages — proves reachability"},
    {"key": "wa_read_date", "label": "WhatsApp last read (Read Date)", "group": "Momentum & promise", "desc": "Last time a WhatsApp message was read"},

    {"key": "months_on_book", "label": "Months on book (MOB / Vintage)", "group": "Profile & vintage", "desc": "Account age in months — drives vintage analysis"},
    {"key": "lent_date", "label": "Disbursal date", "group": "Profile & vintage", "desc": "Loan disbursal date — used for vintage"},
    {"key": "borrower_name", "label": "Borrower name", "group": "Profile & vintage", "desc": "Shown on the worklist for identification"},
    {"key": "rm_name", "label": "RM / collector name", "group": "Profile & vintage", "desc": "Relationship manager or collector assigned"},
    {"key": "age", "label": "Age", "group": "Profile & vintage", "desc": "Borrower age"},

    {"key": "label_actual_payment", "label": "Did pay (label)", "group": "Labels (optional)", "desc": "1/0 outcome, to evaluate model accuracy"},
    {"key": "label_promise_to_pay", "label": "Promised to pay (label)", "group": "Labels (optional)", "desc": "1/0 promise, to evaluate model accuracy"},
]


def _parse_outcomes(path):
    """Read an outcomes CSV/XLSX flexibly: account id + paid(+amount+action)."""
    import csv as _csv
    import io as _io
    import re as _re
    hdrs, _fmt = ingest.read_headers(path)
    canon = {_re.sub(r"[^a-z0-9]", "", h.lower()): h for h in hdrs}

    def find(*names):
        for n in names:
            k = _re.sub(r"[^a-z0-9]", "", n.lower())
            if k in canon:
                return canon[k]
        return None

    acct_col = find("account_id", "agreement_no", "agreement", "loan_id", "id")
    paid_col = find("paid", "paid_yes_no", "paidflag", "didpay", "settled", "collected", "status")
    amt_col = find("amount", "paid_amount", "collected_amount", "recovery")
    act_col = find("action", "action_taken", "activity")
    text, _enc = ingest._read_text(path) if not ingest._looks_like_xlsx(path) else (None, None)
    out = []
    if text is not None:
        text = text.replace("\r\n", "\n").replace("\r", "\n")
        rdr = _csv.DictReader(_io.StringIO(text, newline=""), delimiter=ingest._sniff_delimiter(text[:4096]))
        rdr.fieldnames = [h.strip() for h in (rdr.fieldnames or [])]
        src = list(rdr)
    else:
        hh, src = ingest._read_xlsx(path)
    for r in src:
        aid = (r.get(acct_col) or "").strip() if acct_col else ""
        if not aid:
            continue
        pv = (str(r.get(paid_col)) if paid_col else "").strip().lower()
        paid = 1 if pv in ("1", "y", "yes", "true", "paid", "settled", "collected", "success") else 0
        amt = 0.0
        if amt_col:
            amt = ingest._to_num(r.get(amt_col)) or 0.0
        out.append({"account_id": aid, "paid": paid, "amount": amt,
                    "action_taken": (r.get(act_col) or "").strip() if act_col else None})
    return out


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


def score_rows(rows, report, collectors, top_field, lender="default"):
    global LAST_WORKLIST
    labeled = [r for r in rows if r.get("label_actual_payment") is not None]
    has_labels = len(labeled) == len(rows) and len(rows) > 0
    n_pos = sum(1 for r in labeled if int(float(r["label_actual_payment"])) == 1)

    # If the uploaded book carries its own repayment outcomes, train a real
    # supervised model on THIS data and use it (provenance="self"). Otherwise we
    # never trust the bundled/reference model on real data — scoring falls back
    # to the learned-from-outcomes model or a transparent heuristic.
    if has_labels and len(rows) >= 40 and 0 < n_pos < len(labeled):
        try:
            model = pipeline.Aayudh().fit(rows)
            results = model.score(rows, n_collectors=collectors, top_k_field=top_field,
                                  lender=lender, model_provenance="self")
        except Exception as e:
            print("self-train failed, using reference model + heuristic:", e)
            results = MODEL.score(rows, n_collectors=collectors, top_k_field=top_field,
                                  lender=lender, model_provenance="reference")
    else:
        results = MODEL.score(rows, n_collectors=collectors, top_k_field=top_field,
                              lender=lender, model_provenance="reference")
    results["ingest_report"] = report
    results["lender"] = lender
    results["uploaded_labels_present"] = has_labels

    # Persist to memory so the model can learn from later feedback/outcomes.
    try:
        run_id = memory.record_run(lender, len(results["worklist"]), {"confidence": results["summary"].get("confidence")})
        memory.record_scored(run_id, lender, results["worklist"])
    except Exception as e:
        print("memory persist failed:", e)

    LAST_WORKLIST = list(results["worklist"])  # keep the FULL list for CSV export
    # Strip internal feature vectors from the browser payload.
    for w in results["worklist"]:
        w.pop("_features", None)
    return _trim(results)


# Columns exported to CSV, in order.
EXPORT_COLUMNS = [
    "rank", "account_id", "borrower_name", "region", "rm_name", "segment",
    "current_bucket", "days_past_due", "priority_level", "priority_score",
    "intent_to_pay", "intent_score", "needs_review", "review_reason", "ptp_active",
    "prob_promise_to_pay", "prob_actual_payment", "pay_source",
    "pos", "past_due_amount", "recoverable_amount", "expected_payment", "topsis_score",
    "strategy", "strategy_path", "first_action", "first_script", "est_cost",
    "preferred_channel", "reason",
]


def worklist_to_csv(worklist):
    import csv
    import io
    buf = io.StringIO()
    w = csv.writer(buf)
    w.writerow(EXPORT_COLUMNS)
    for row in worklist:
        w.writerow([
            row.get("rank"), row.get("account_id"), row.get("borrower_name"),
            row.get("region"), row.get("rm_name"), row.get("segment"),
            row.get("current_bucket"), row.get("days_past_due"),
            row.get("priority_level"), row.get("priority_score"),
            row.get("intent_band"), row.get("intent_score"),
            row.get("review"), row.get("review_reason"), row.get("ptp_active"),
            row.get("prob_promise_to_pay"), row.get("prob_actual_payment"),
            row.get("audit", {}).get("pay_source"),
            row.get("pos"), row.get("past_due_amount"), row.get("recoverable_amount"),
            row.get("expected_payment"), row.get("topsis_score"),
            (row.get("strategy") or {}).get("label"),
            " > ".join(t["channel"] for t in (row.get("strategy") or {}).get("touches", [])),
            (row.get("strategy") or {}).get("first_action"),
            (row.get("strategy") or {}).get("first_script"),
            (row.get("strategy") or {}).get("est_cost"),
            row.get("preferred_channel"),
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
            results = score_rows(list(TRAIN_ROWS), {"note": "bundled synthetic sample"}, 3, 90, lender="sample")
            results["source"] = "synthetic sample"
            self._send(200, results)
        elif path == "/api/memory":
            lender = parse_qs(urlparse(self.path).query).get("lender", [None])[0]
            self._send(200, {"stats": memory.stats(lender), "lender": lender})
        elif path == "/api/sample-csv":
            # Hand back a small, correctly-shaped CSV users can edit and re-upload.
            with open(DATA) as f:
                head = "".join([next(f) for _ in range(26)])
            self.send_response(200)
            self.send_header("Content-Type", "text/csv")
            self.send_header("Content-Disposition", "attachment; filename=aayudh_sample.csv")
            self.send_header("Content-Length", str(len(head.encode())))
            self.end_headers()
            self.wfile.write(head.encode())
        elif path == "/api/export.csv":
            if not LAST_WORKLIST:
                return self._send(400, {"error": "nothing to export yet; score a file first"})
            body = worklist_to_csv(LAST_WORKLIST).encode("utf-8-sig")  # BOM so Excel opens it clean
            self.send_response(200)
            self.send_header("Content-Type", "text/csv")
            self.send_header("Content-Disposition", "attachment; filename=aayudh_worklist.csv")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        else:
            self._send(404, {"error": "not found"})

    def _read_body(self):
        length = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(length)

    def do_POST(self):
        path = self.path.split("?")[0]
        if path in ("/api/score", "/api/inspect"):
            return self._handle_upload(path)
        if path == "/api/feedback":
            return self._handle_feedback()
        if path == "/api/outcomes":
            return self._handle_outcomes()
        if path == "/api/learn":
            return self._handle_learn()
        return self._send(404, {"error": "not found"})

    def _handle_upload(self, path):
        ctype = self.headers.get("Content-Type", "")
        if "multipart/form-data" not in ctype:
            return self._send(400, {"error": "expected a multipart file upload"})
        fields, files = parse_multipart(self.headers, self._read_body())
        if "file" not in files or not files["file"]["content"]:
            return self._send(400, {"error": "no file uploaded"})
        filename = files["file"].get("filename") or "upload"

        tmp = tempfile.NamedTemporaryFile(mode="wb", suffix=".dat", delete=False)
        try:
            tmp.write(files["file"]["content"])
            tmp.close()

            if path == "/api/inspect":
                headers, fmt = ingest.read_headers(tmp.name)
                if not headers:
                    return self._send(400, {"error": "could not read a header row from the file"})
                return self._send(200, {
                    "source_columns": headers, "format": fmt,
                    "suggested": ingest.suggest_mapping(headers),
                    "fields": MAP_FIELDS, "filename": filename,
                })

            mapping = None
            if fields.get("mapping"):
                try:
                    mapping = json.loads(fields["mapping"])
                except (ValueError, TypeError):
                    mapping = None
            lender = (fields.get("lender") or "default").strip() or "default"
            collectors = int(fields.get("collectors", "3") or 3)
            top_field = int(fields.get("top_field", "90") or 90)
            rows, report = ingest.load_csv(tmp.name, mapping=mapping)
            if not rows:
                return self._send(400, {"error": "no data rows found in file"})
            results = score_rows(rows, report, collectors, top_field, lender=lender)
            results["source"] = filename
            self._send(200, results)
        except Exception as e:
            self._send(400, {"error": f"could not process file: {e}"})
        finally:
            os.unlink(tmp.name)

    def _handle_feedback(self):
        """Human-in-the-loop: override / approve / correct-KB, all captured."""
        try:
            d = json.loads(self._read_body() or b"{}")
        except ValueError:
            return self._send(400, {"error": "invalid JSON"})
        lender = (d.get("lender") or "default").strip() or "default"
        if not d.get("account_id") or not d.get("kind"):
            return self._send(400, {"error": "account_id and kind are required"})
        memory.record_feedback(
            lender, d["account_id"], d["kind"],
            predicted_level=d.get("predicted_level"), corrected_level=d.get("corrected_level"),
            predicted_action=d.get("predicted_action"), corrected_action=d.get("corrected_action"),
            note=d.get("note"), reviewer=d.get("reviewer", "user"),
        )
        self._send(200, {"ok": True, "stats": memory.stats(lender)})

    def _handle_outcomes(self):
        """Upload a CSV of outcomes (account_id, paid, amount, action) to learn from."""
        ctype = self.headers.get("Content-Type", "")
        if "multipart/form-data" not in ctype:
            return self._send(400, {"error": "expected a CSV upload"})
        fields, files = parse_multipart(self.headers, self._read_body())
        if "file" not in files or not files["file"]["content"]:
            return self._send(400, {"error": "no file uploaded"})
        lender = (fields.get("lender") or "default").strip() or "default"
        tmp = tempfile.NamedTemporaryFile(mode="wb", suffix=".csv", delete=False)
        try:
            tmp.write(files["file"]["content"])
            tmp.close()
            rows = _parse_outcomes(tmp.name)
            if not rows:
                return self._send(400, {"error": "no usable outcome rows (need account id + paid)"})
            n = memory.record_outcomes(lender, rows)
            summary = learner.learn(lender)  # learn immediately
            self._send(200, {"ok": True, "recorded": n, "learn": summary, "stats": memory.stats(lender)})
        except Exception as e:
            self._send(400, {"error": f"could not process outcomes: {e}"})
        finally:
            os.unlink(tmp.name)

    def _handle_learn(self):
        try:
            d = json.loads(self._read_body() or b"{}")
        except ValueError:
            d = {}
        lender = (d.get("lender") or "default").strip() or "default"
        summary = learner.learn(lender)
        self._send(200, {"ok": True, "learn": summary, "stats": memory.stats(lender)})


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8000)
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--n-synth", type=int, default=5000)
    args = ap.parse_args()

    bootstrap(args.n_synth)
    srv = ThreadingHTTPServer((args.host, args.port), Handler)
    url = f"http://{args.host}:{args.port}"
    print(f"Aayudh is running at {url}  (Ctrl-C to stop)")
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        print("\nStopping.")
        srv.shutdown()


if __name__ == "__main__":
    main()
