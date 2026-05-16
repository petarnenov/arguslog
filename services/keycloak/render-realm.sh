#!/usr/bin/env bash
# Renders services/keycloak/realm.template.json -> realm/arguslog-realm.json with placeholder
# values substituted from the environment. Called by `make up` before docker-compose starts
# Keycloak so the realm import sees the right redirect URIs and IdP credentials.
#
# Placeholders rendered:
#   __DEV_HOST__              ← $DEV_HOST   (default: localhost)
#   __GITHUB_CLIENT_ID__      ← $GITHUB_CLIENT_ID     (default: empty → IdP stripped)
#   __GITHUB_CLIENT_SECRET__  ← $GITHUB_CLIENT_SECRET (default: empty → IdP stripped)
#   __GOOGLE_CLIENT_ID__      ← $GOOGLE_CLIENT_ID     (default: empty → IdP stripped)
#   __GOOGLE_CLIENT_SECRET__  ← $GOOGLE_CLIENT_SECRET (default: empty → IdP stripped)
#   __GITLAB_CLIENT_ID__      ← $GITLAB_CLIENT_ID     (default: empty → IdP stripped)
#   __GITLAB_CLIENT_SECRET__  ← $GITLAB_CLIENT_SECRET (default: empty → IdP stripped)
#
# If an IdP's clientId comes through empty, the rendered file post-processes the
# identityProviders array to drop that entry — Keycloak otherwise renders a broken-looking
# button on the login page. Self-hosters with no OAuth apps get a clean email/password realm.
#
# Idempotent: re-running with the same env regenerates the same file.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEMPLATE="$SCRIPT_DIR/realm.template.json"
OUTPUT="$SCRIPT_DIR/realm/arguslog-realm.json"

if [ ! -f "$TEMPLATE" ]; then
  echo "✗ realm template missing at $TEMPLATE" >&2
  exit 1
fi

DEV_HOST="${DEV_HOST:-localhost}"
GITHUB_CLIENT_ID="${GITHUB_CLIENT_ID:-}"
GITHUB_CLIENT_SECRET="${GITHUB_CLIENT_SECRET:-}"
GOOGLE_CLIENT_ID="${GOOGLE_CLIENT_ID:-}"
GOOGLE_CLIENT_SECRET="${GOOGLE_CLIENT_SECRET:-}"
GITLAB_CLIENT_ID="${GITLAB_CLIENT_ID:-}"
GITLAB_CLIENT_SECRET="${GITLAB_CLIENT_SECRET:-}"

mkdir -p "$(dirname "$OUTPUT")"

# sed (BSD/GNU compatible) for the substitution pass — envsubst isn't installed by default
# on macOS. Placeholders are __ALL_CAPS__ which never appear legitimately in JSON values.
sed \
  -e "s|__DEV_HOST__|${DEV_HOST}|g" \
  -e "s|__GITHUB_CLIENT_ID__|${GITHUB_CLIENT_ID}|g" \
  -e "s|__GITHUB_CLIENT_SECRET__|${GITHUB_CLIENT_SECRET}|g" \
  -e "s|__GOOGLE_CLIENT_ID__|${GOOGLE_CLIENT_ID}|g" \
  -e "s|__GOOGLE_CLIENT_SECRET__|${GOOGLE_CLIENT_SECRET}|g" \
  -e "s|__GITLAB_CLIENT_ID__|${GITLAB_CLIENT_ID}|g" \
  -e "s|__GITLAB_CLIENT_SECRET__|${GITLAB_CLIENT_SECRET}|g" \
  "$TEMPLATE" > "$OUTPUT"

# Drop any identityProviders entry whose clientId came through empty — they would otherwise
# render dead buttons on the Keycloak login page. jq is a documented dev prereq (make doctor).
if command -v jq >/dev/null 2>&1; then
  TMP="$(mktemp)"
  jq '(.identityProviders // []) |= map(select(.config.clientId != "" and .config.clientId != null))' \
    "$OUTPUT" > "$TMP"
  mv "$TMP" "$OUTPUT"
else
  echo "⚠ jq not found — IdP entries with empty credentials will render as broken buttons. Install jq." >&2
fi

# Surface IdP state in the render log so the operator knows what landed in the realm.
github_state="disabled"
google_state="disabled"
gitlab_state="disabled"
[ -n "$GITHUB_CLIENT_ID" ] && [ -n "$GITHUB_CLIENT_SECRET" ] && github_state="enabled"
[ -n "$GOOGLE_CLIENT_ID" ] && [ -n "$GOOGLE_CLIENT_SECRET" ] && google_state="enabled"
[ -n "$GITLAB_CLIENT_ID" ] && [ -n "$GITLAB_CLIENT_SECRET" ] && gitlab_state="enabled"

echo "✓ Rendered Keycloak realm with DEV_HOST=${DEV_HOST}, github=${github_state}, google=${google_state}, gitlab=${gitlab_state} -> $OUTPUT"
