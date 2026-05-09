#!/usr/bin/env bash
# Renders services/keycloak/realm.template.json -> realm/arguslog-realm.json with the active
# DEV_HOST substituted. Called by `make up` before docker-compose starts Keycloak so the
# realm import sees redirect URIs that match whatever host devs are loading the dashboard
# from. DEV_HOST defaults to "localhost", which produces a file functionally identical to
# the pre-templating realm — duplicate localhost entries Keycloak collapses on import.
#
# Idempotent: re-running with the same DEV_HOST regenerates the same file.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEMPLATE="$SCRIPT_DIR/realm.template.json"
OUTPUT="$SCRIPT_DIR/realm/arguslog-realm.json"

if [ ! -f "$TEMPLATE" ]; then
  echo "✗ realm template missing at $TEMPLATE" >&2
  exit 1
fi

DEV_HOST="${DEV_HOST:-localhost}"
mkdir -p "$(dirname "$OUTPUT")"

# Use sed (BSD/GNU compatible) instead of envsubst — envsubst isn't installed by default on
# macOS, sed is everywhere. The placeholder is __DEV_HOST__ which never appears legitimately
# in JSON values.
sed "s|__DEV_HOST__|${DEV_HOST}|g" "$TEMPLATE" > "$OUTPUT"

echo "✓ Rendered Keycloak realm with DEV_HOST=$DEV_HOST -> $OUTPUT"
