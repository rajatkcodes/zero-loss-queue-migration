#!/usr/bin/env bash
# Brings up infra + all 5 backend services + the frontend dev server.
# Run from the project root: ./scripts/run-all.sh
set -euo pipefail
cd "$(dirname "$0")/.."

export PATH="$HOME/.local/bin:$PATH"
mkdir -p logs

echo "==> starting infra (redis + kafka + zookeeper)"
docker compose up -d

echo "==> waiting for kafka to accept connections"
for i in $(seq 1 30); do
  if docker exec swym-showcase-redis redis-cli ping >/dev/null 2>&1; then break; fi
  sleep 1
done
sleep 5

start() {
  local name="$1"; shift
  echo "==> starting $name"
  nohup "$@" > "logs/${name}.log" 2>&1 &
  echo $! > "logs/${name}.pid"
}

start control-plane      clojure -M -m swym.services.control-plane
start metrics-aggregator clojure -M -m swym.services.metrics-aggregator
sleep 3
start legacy-consumer    clojure -M -m swym.services.legacy-consumer
start new-consumer       clojure -M -m swym.services.new-consumer
start event-generator    clojure -M -m swym.services.event-generator

echo "==> starting frontend"
(cd frontend && nohup npm run dev > ../logs/frontend.log 2>&1 & echo $! > ../logs/frontend.pid)

sleep 3
echo ""
echo "control-plane API : http://localhost:8901/api/health"
echo "frontend          : http://localhost:5173 (see logs/frontend.log for the actual port)"
echo "logs               : ./logs/*.log"
echo "stop everything    : ./scripts/stop-all.sh"
