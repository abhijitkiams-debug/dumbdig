"""
Build the self-contained demo/index.html by inlining the distilled data payload
into demo/index_template.html (replacing the __RECOUP_DATA__ marker). Run after
run.py + make_demo_data.py.
"""

import json
import os

HERE = os.path.dirname(__file__)
DEMO = os.path.abspath(os.path.join(HERE, "..", "demo"))
RESULTS = os.path.abspath(os.path.join(HERE, "..", "output", "results.json"))


def distill():
    with open(RESULTS) as f:
        r = json.load(f)
    wl = r["worklist"]
    highs = [w for w in wl if w["priority_level"] == "High"][:26]
    meds = [w for w in wl if w["priority_level"] == "Medium"][:14]
    lows = [w for w in wl if w["priority_level"] == "Low"][:10]
    curated = sorted(highs + meds + lows, key=lambda w: w["rank"])
    return {
        "summary": r["summary"],
        "model_metrics": r["model_metrics"],
        "ahp_consistency_ratio": r["ahp_consistency_ratio"],
        "ahp_action_ranking": r["ahp_action_ranking"],
        "routes": r["routes"],
        "worklist": curated,
    }


def main():
    payload = distill()
    data_js = "window.RECOUP_DATA = " + json.dumps(payload) + ";"
    with open(os.path.join(DEMO, "index_template.html")) as f:
        html = f.read()
    html = html.replace("/*__RECOUP_DATA__*/", data_js)
    out = os.path.join(DEMO, "index.html")
    with open(out, "w") as f:
        f.write(html)
    print(f"Wrote {out} ({len(html):,} bytes, {len(payload['worklist'])} worklist rows)")


if __name__ == "__main__":
    main()
