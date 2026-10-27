#!/usr/bin/env sh
set -eu
ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT_DIR"
./scripts/generate-jwt-keys.sh
# Only disposable containers in this explicitly named test project are removed.
docker compose -p household-planner-tests -f compose.test.yaml down --volumes
cleanup() {
  result=$?
  if [ "$result" -ne 0 ]; then docker compose -p household-planner-tests -f compose.test.yaml logs api; fi
  docker compose -p household-planner-tests -f compose.test.yaml down --volumes
}
trap cleanup EXIT
docker compose -p household-planner-tests -f compose.test.yaml up -d --build --wait api
export VITE_DEV_API_TARGET=http://127.0.0.1:8081
export E2E_API_URL=http://127.0.0.1:8081
export MAILPIT_URL=http://127.0.0.1:8026
npm run test:e2e --prefix web
