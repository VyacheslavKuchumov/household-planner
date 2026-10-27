#!/usr/bin/env sh
set -eu
ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT_DIR"
./scripts/generate-jwt-keys.sh
export DB_URL="${DB_URL:-jdbc:postgresql://localhost:${DEV_DB_PORT:-5433}/${DEV_DB_NAME:-household_planner_dev}}"
export DB_USER="${DB_USER:-${DEV_DB_USER:-household_dev}}"
export DB_PASSWORD="${DB_PASSWORD:-${DEV_DB_PASSWORD:-household_dev_local_only}}"
export JWT_PRIVATE_KEY_FILE="$ROOT_DIR/.local/secrets/jwt-private.pem"
export JWT_PUBLIC_KEY_FILE="$ROOT_DIR/.local/secrets/jwt-public.pem"
export APP_PUBLIC_URL="${APP_PUBLIC_URL:-http://localhost:5173}"
export ALLOWED_ORIGINS="${ALLOWED_ORIGINS:-http://localhost:5173,http://127.0.0.1:5173}"
export COOKIE_SECURE=false
export COOKIE_SAME_SITE=Lax
export SMTP_HOST="${SMTP_HOST:-localhost}"
export SMTP_PORT="${SMTP_PORT:-1025}"
export SMTP_STARTTLS="${SMTP_STARTTLS:-false}"
export MAIL_FROM="${MAIL_FROM:-Household Planner <noreply@localhost.test>}"
exec sbt "backend/run"
