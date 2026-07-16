"""
Persistent memory for Recoup (SQLite, standard library only).

Everything that lets the model keep learning lives here and survives restarts:
scored accounts, human-in-the-loop feedback, real outcomes, the per-lender
knowledge-base state, and the incremental model blob. One file: memory/recoup.db.
"""

import json
import os
import pickle
import sqlite3
import time
import uuid

DB_PATH = os.path.join(os.path.dirname(__file__), "..", "memory", "recoup.db")


def _now():
    return time.strftime("%Y-%m-%dT%H:%M:%S")


def connect():
    os.makedirs(os.path.dirname(os.path.abspath(DB_PATH)), exist_ok=True)
    conn = sqlite3.connect(os.path.abspath(DB_PATH))
    conn.row_factory = sqlite3.Row
    _ensure_schema(conn)
    return conn


def _ensure_schema(conn):
    conn.executescript(
        """
        CREATE TABLE IF NOT EXISTS runs (
            run_id TEXT PRIMARY KEY, lender TEXT, ts TEXT, n INTEGER, meta TEXT);
        CREATE TABLE IF NOT EXISTS scored (
            id INTEGER PRIMARY KEY AUTOINCREMENT, run_id TEXT, lender TEXT,
            account_id TEXT, score REAL, level TEXT, action TEXT, features TEXT, ts TEXT);
        CREATE TABLE IF NOT EXISTS feedback (
            id INTEGER PRIMARY KEY AUTOINCREMENT, lender TEXT, account_id TEXT,
            kind TEXT, predicted_level TEXT, corrected_level TEXT,
            predicted_action TEXT, corrected_action TEXT, note TEXT, reviewer TEXT, ts TEXT);
        CREATE TABLE IF NOT EXISTS outcomes (
            id INTEGER PRIMARY KEY AUTOINCREMENT, lender TEXT, account_id TEXT,
            paid INTEGER, amount REAL, action_taken TEXT, ts TEXT);
        CREATE TABLE IF NOT EXISTS kb_state (
            lender TEXT PRIMARY KEY, json TEXT, ts TEXT);
        CREATE TABLE IF NOT EXISTS model_state (
            lender TEXT PRIMARY KEY, blob BLOB, meta TEXT, ts TEXT);
        CREATE INDEX IF NOT EXISTS idx_scored_acct ON scored(lender, account_id);
        CREATE INDEX IF NOT EXISTS idx_fb_acct ON feedback(lender, account_id);
        CREATE INDEX IF NOT EXISTS idx_out_acct ON outcomes(lender, account_id);
        """
    )
    conn.commit()


def init():
    """Explicit initialization (schema is also ensured lazily on every connect)."""
    connect().close()


# ---- writes ---------------------------------------------------------------
def record_run(lender, n, meta=None):
    run_id = uuid.uuid4().hex[:12]
    conn = connect()
    conn.execute("INSERT INTO runs VALUES (?,?,?,?,?)",
                 (run_id, lender, _now(), n, json.dumps(meta or {})))
    conn.commit()
    conn.close()
    return run_id


def record_scored(run_id, lender, worklist):
    conn = connect()
    ts = _now()
    conn.executemany(
        "INSERT INTO scored (run_id,lender,account_id,score,level,action,features,ts) VALUES (?,?,?,?,?,?,?,?)",
        [(run_id, lender, w.get("account_id"), w.get("priority_score"), w.get("priority_level"),
          w.get("next_best_action"), json.dumps(w.get("_features", {})), ts) for w in worklist],
    )
    conn.commit()
    conn.close()


def record_feedback(lender, account_id, kind, predicted_level=None, corrected_level=None,
                    predicted_action=None, corrected_action=None, note=None, reviewer="user"):
    conn = connect()
    conn.execute(
        "INSERT INTO feedback (lender,account_id,kind,predicted_level,corrected_level,predicted_action,corrected_action,note,reviewer,ts) VALUES (?,?,?,?,?,?,?,?,?,?)",
        (lender, account_id, kind, predicted_level, corrected_level, predicted_action, corrected_action, note, reviewer, _now()),
    )
    conn.commit()
    conn.close()


def record_outcomes(lender, rows):
    """rows: list of dicts {account_id, paid(0/1), amount, action_taken}."""
    conn = connect()
    ts = _now()
    conn.executemany(
        "INSERT INTO outcomes (lender,account_id,paid,amount,action_taken,ts) VALUES (?,?,?,?,?,?)",
        [(lender, r.get("account_id"), int(bool(r.get("paid"))), float(r.get("amount") or 0),
          r.get("action_taken"), ts) for r in rows],
    )
    conn.commit()
    conn.close()
    return len(rows)


# ---- reads ----------------------------------------------------------------
def latest_features(lender, account_ids):
    """Most recent scored feature vector per account (for learning joins)."""
    conn = connect()
    out = {}
    for aid in account_ids:
        row = conn.execute(
            "SELECT features, level, action FROM scored WHERE lender=? AND account_id=? ORDER BY id DESC LIMIT 1",
            (lender, aid)).fetchone()
        if row:
            out[aid] = {"features": json.loads(row["features"] or "{}"),
                        "level": row["level"], "action": row["action"]}
    conn.close()
    return out


def get_feedback(lender):
    conn = connect()
    rows = conn.execute("SELECT * FROM feedback WHERE lender=? ORDER BY id", (lender,)).fetchall()
    conn.close()
    return [dict(r) for r in rows]


def get_outcomes(lender):
    conn = connect()
    rows = conn.execute("SELECT * FROM outcomes WHERE lender=? ORDER BY id", (lender,)).fetchall()
    conn.close()
    return [dict(r) for r in rows]


def stats(lender=None):
    conn = connect()

    def one(q, args=()):
        return conn.execute(q, args).fetchone()[0]

    where = " WHERE lender=?" if lender else ""
    args = (lender,) if lender else ()
    s = {
        "runs": one("SELECT COUNT(*) FROM runs" + where, args),
        "scored": one("SELECT COUNT(*) FROM scored" + where, args),
        "feedback": one("SELECT COUNT(*) FROM feedback" + where, args),
        "outcomes": one("SELECT COUNT(*) FROM outcomes" + where, args),
        "lenders": one("SELECT COUNT(DISTINCT lender) FROM scored"),
    }
    conn.close()
    return s


# ---- KB + model persistence ----------------------------------------------
def load_kb(lender):
    conn = connect()
    row = conn.execute("SELECT json FROM kb_state WHERE lender=?", (lender,)).fetchone()
    conn.close()
    return json.loads(row["json"]) if row else None


def save_kb(lender, state):
    conn = connect()
    conn.execute("INSERT INTO kb_state (lender,json,ts) VALUES (?,?,?) "
                 "ON CONFLICT(lender) DO UPDATE SET json=excluded.json, ts=excluded.ts",
                 (lender, json.dumps(state), _now()))
    conn.commit()
    conn.close()


def load_model(lender):
    conn = connect()
    row = conn.execute("SELECT blob, meta FROM model_state WHERE lender=?", (lender,)).fetchone()
    conn.close()
    if not row:
        return None, None
    return pickle.loads(row["blob"]), json.loads(row["meta"] or "{}")


def save_model(lender, model, meta=None):
    conn = connect()
    conn.execute("INSERT INTO model_state (lender,blob,meta,ts) VALUES (?,?,?,?) "
                 "ON CONFLICT(lender) DO UPDATE SET blob=excluded.blob, meta=excluded.meta, ts=excluded.ts",
                 (lender, pickle.dumps(model), json.dumps(meta or {}), _now()))
    conn.commit()
    conn.close()
