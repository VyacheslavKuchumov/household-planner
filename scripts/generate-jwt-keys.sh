#!/usr/bin/env sh
set -eu
ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
KEY_DIR="$ROOT_DIR/.local/secrets"
umask 077
mkdir -p "$KEY_DIR"
if ! mkdir "$KEY_DIR/.generation-lock" 2>/dev/null; then
  printf '%s\n' 'Another JWT key generation is in progress.' >&2
  exit 1
fi
trap 'rmdir "$KEY_DIR/.generation-lock"' EXIT
if [ -f "$KEY_DIR/jwt-private.pem" ] && [ -f "$KEY_DIR/jwt-public.pem" ]; then
  printf '%s\n' 'JWT keys already exist; preserving them.'
  exit 0
fi
if [ -e "$KEY_DIR/jwt-private.pem" ] || [ -e "$KEY_DIR/jwt-public.pem" ]; then
  printf '%s\n' 'Only one JWT key exists. Restore the matching key pair before continuing.' >&2
  exit 1
fi
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out "$KEY_DIR/jwt-private.tmp"
openssl pkey -in "$KEY_DIR/jwt-private.tmp" -pubout -out "$KEY_DIR/jwt-public.tmp"
mv "$KEY_DIR/jwt-private.tmp" "$KEY_DIR/jwt-private.pem"
mv "$KEY_DIR/jwt-public.tmp" "$KEY_DIR/jwt-public.pem"
printf '%s\n' 'JWT key pair created in .local/secrets/.'
