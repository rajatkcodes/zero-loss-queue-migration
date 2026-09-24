#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

for f in logs/*.pid; do
  [ -f "$f" ] || continue
  pid="$(cat "$f")"
  kill "$pid" 2>/dev/null || true
  rm -f "$f"
done

echo "==> backend/frontend processes stopped. Infra left running (docker compose down to stop it)."
