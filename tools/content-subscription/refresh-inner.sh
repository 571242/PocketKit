#!/usr/bin/env bash
# Runs inside pocketkit-content-sync.service as the low-privilege pk-content user.
# Real-chain bounded test of candidate book sources (novel + manga + video); never
# overwrites a working package with an empty one (curator guarantees last-good on all-fail).
set -euo pipefail
DATA_ROOT="${PK_CONTENT_DIR:-/data/pocketkit-content}"
PKG="$DATA_ROOT/content/book-sources.json"
/usr/bin/python3 /opt/pocketkit-content/curator.py \
  --data-dir "$DATA_ROOT" --max-test "${PK_MAX_TEST:-60}" || status=$?
# Even when this run only keeps last-good, normalise the existing package. A non-zero
# status means "no fresh publish", not "delete the working subscription".
if [ -f "$PKG" ]; then
  /usr/bin/python3 /opt/pocketkit-content/finalize_sources.py --package "$PKG"
fi
exit "${status:-0}"
