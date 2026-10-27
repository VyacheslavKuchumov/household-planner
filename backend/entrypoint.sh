#!/usr/bin/env sh
set -eu
# Compose file secrets may retain host permissions. Read them once as root,
# then run the JVM as the application user with private copies on tmpfs.
mkdir -p /run/household
chmod 700 /run/household
cp "$JWT_PRIVATE_KEY_FILE" /run/household/jwt-private.pem
cp "$JWT_PUBLIC_KEY_FILE" /run/household/jwt-public.pem
chown -R app:app /run/household
chmod 400 /run/household/jwt-private.pem /run/household/jwt-public.pem
export JWT_PRIVATE_KEY_FILE=/run/household/jwt-private.pem
export JWT_PUBLIC_KEY_FILE=/run/household/jwt-public.pem
exec su-exec app /opt/app/bin/household-planner-api
