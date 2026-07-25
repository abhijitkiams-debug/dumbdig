#!/usr/bin/env bash
# Launch the demo on a no-cache local server so the browser can never run a
# stale JS bundle (that's what makes a device keep showing an already-fixed bug).
# Usage: ./run.sh [port]   (default 8000)
set -euo pipefail
cd "$(dirname "$0")"
PORT="${1:-8000}"
echo "Setu Finance demo -> http://localhost:${PORT}/index.html"
echo "(no-store cache headers; append ?debug=1 to see STT diagnostics)"
exec python3 serve.py "$PORT"
