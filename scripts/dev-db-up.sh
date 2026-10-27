#!/usr/bin/env sh
set -eu
ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT_DIR"
COMPOSE='docker compose -f compose.dev.yaml'
$COMPOSE up -d --wait db mailpit
$COMPOSE run --rm migrate
$COMPOSE exec -T db sh -c 'exec psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"' < scripts/dev-seed-base.sql
printf '%s\n' 'Локальная PostgreSQL готова. Для демо-данных запусти scripts/dev-db-seed-demo.sh.'
