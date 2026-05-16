#!/usr/bin/env bash
# Post-boot Keycloak Admin-API patcher for social-login identity providers.
#
# Runs from the Dockerfile entrypoint AFTER kc.sh start brings the server up. Reads four env
# vars (GITHUB_CLIENT_ID, GITHUB_CLIENT_SECRET, GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET) and
# upserts or deletes the matching IdP via PUT/DELETE on
# /admin/realms/arguslog/identity-provider/instances/{alias}.
#
# Idempotent — safe to run on every container start. Realm import (-­-import-realm) only
# happens once, so this script is the authoritative way to keep long-running prod realms in
# sync with the env-var-supplied credentials.

set -u -o pipefail

KC_URL="${KC_URL:-http://localhost:8080}"
REALM="${REALM:-arguslog}"
ADMIN_USER="${KC_BOOTSTRAP_ADMIN_USERNAME:-${KEYCLOAK_ADMIN:-admin}}"
ADMIN_PASS="${KC_BOOTSTRAP_ADMIN_PASSWORD:-${KEYCLOAK_ADMIN_PASSWORD:-admin}}"

log() { printf '[configure-idps] %s\n' "$*"; }
die() { log "FATAL: $*"; exit 1; }

require_tool() {
  command -v "$1" >/dev/null 2>&1 || die "$1 is required (install in the Keycloak image)."
}

require_tool curl
require_tool jq

# ── 1. wait for Keycloak readiness ──────────────────────────────────────
log "waiting for Keycloak at $KC_URL"
for i in $(seq 1 60); do
  if curl -fsS "${KC_URL}/health/ready" >/dev/null 2>&1 \
      || curl -fsS "${KC_URL}/realms/master" >/dev/null 2>&1; then
    log "Keycloak ready after ${i}s"
    break
  fi
  if [ "$i" -eq 60 ]; then
    die "Keycloak never answered /health/ready in 60s — bailing"
  fi
  sleep 1
done

# ── 2. admin token ──────────────────────────────────────────────────────
ADMIN_TOKEN_RAW="$(curl -sS -X POST \
  "${KC_URL}/realms/master/protocol/openid-connect/token" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d "username=${ADMIN_USER}" \
  -d "password=${ADMIN_PASS}" \
  -d 'grant_type=password' \
  -d 'client_id=admin-cli')"

ADMIN_TOKEN="$(printf '%s' "$ADMIN_TOKEN_RAW" | jq -r '.access_token // empty')"
if [ -z "$ADMIN_TOKEN" ] || [ "$ADMIN_TOKEN" = "null" ]; then
  log "could not authenticate as ${ADMIN_USER}; response: ${ADMIN_TOKEN_RAW}"
  log "skipping IdP configuration — Keycloak still serves email/password login"
  exit 0
fi

api() {
  # api METHOD PATH [BODY]
  local method="$1" path="$2" body="${3:-}"
  if [ -n "$body" ]; then
    curl -sS -o /dev/null -w '%{http_code}' -X "$method" \
      "${KC_URL}/admin/realms/${REALM}${path}" \
      -H "Authorization: Bearer ${ADMIN_TOKEN}" \
      -H 'Content-Type: application/json' \
      -d "$body"
  else
    curl -sS -o /dev/null -w '%{http_code}' -X "$method" \
      "${KC_URL}/admin/realms/${REALM}${path}" \
      -H "Authorization: Bearer ${ADMIN_TOKEN}"
  fi
}

# ── 3. ensure the `auto-link` flow exists ───────────────────────────────
ensure_auto_link_flow() {
  local existing
  existing="$(curl -fsS "${KC_URL}/admin/realms/${REALM}/authentication/flows" \
    -H "Authorization: Bearer ${ADMIN_TOKEN}" \
    | jq -r '.[] | select(.alias=="auto-link") | .alias')"
  if [ "$existing" = "auto-link" ]; then
    log "flow 'auto-link' already exists"
    return 0
  fi

  log "creating auto-link flow"
  local code
  code="$(api POST /authentication/flows '{
    "alias": "auto-link",
    "description": "First-broker-login flow that silently links a fresh IdP login to an existing email-matched user — no consent screen.",
    "providerId": "basic-flow",
    "topLevel": true,
    "builtIn": false
  }')"
  if [ "$code" != "201" ]; then
    log "flow create returned HTTP ${code}; continuing"
    return 0
  fi

  for authn in idp-create-user-if-unique idp-auto-link; do
    api POST "/authentication/flows/auto-link/executions/execution" \
      "$(jq -n --arg p "$authn" '{provider: $p}')" >/dev/null
  done
  log "flow 'auto-link' created with 2 alternative executions"
}

# ── 4. upsert / delete each IdP from env-supplied credentials ───────────
upsert_idp() {
  local alias="$1" provider_id="$2" client_id="$3" client_secret="$4" scope="$5"
  if [ -z "$client_id" ] || [ -z "$client_secret" ]; then
    log "${alias}: no credentials in env; ensuring instance is removed"
    api DELETE "/identity-provider/instances/${alias}" >/dev/null || true
    return 0
  fi

  local body
  body="$(jq -n \
    --arg alias "$alias" \
    --arg provider_id "$provider_id" \
    --arg client_id "$client_id" \
    --arg client_secret "$client_secret" \
    --arg scope "$scope" \
    '{
      alias: $alias,
      providerId: $provider_id,
      enabled: true,
      trustEmail: true,
      storeToken: false,
      linkOnly: false,
      firstBrokerLoginFlowAlias: "auto-link",
      config: {
        clientId: $client_id,
        clientSecret: $client_secret,
        syncMode: "IMPORT",
        defaultScope: $scope
      }
    }')"

  local code
  code="$(api PUT "/identity-provider/instances/${alias}" "$body")"
  if [ "$code" = "204" ] || [ "$code" = "201" ]; then
    log "${alias}: enabled (HTTP ${code})"
  elif [ "$code" = "404" ]; then
    # PUT on missing instance — fall back to POST create
    code="$(api POST '/identity-provider/instances' "$body")"
    if [ "$code" = "201" ]; then
      log "${alias}: created (HTTP 201)"
    else
      log "${alias}: create returned HTTP ${code}"
    fi
  else
    log "${alias}: PUT returned HTTP ${code}; check Keycloak logs"
  fi
}

ensure_auto_link_flow

upsert_idp "github" "github" \
  "${GITHUB_CLIENT_ID:-}" "${GITHUB_CLIENT_SECRET:-}" \
  "user:email read:user"

upsert_idp "google" "google" \
  "${GOOGLE_CLIENT_ID:-}" "${GOOGLE_CLIENT_SECRET:-}" \
  "openid email profile"

github_state="disabled"; google_state="disabled"
[ -n "${GITHUB_CLIENT_ID:-}" ] && [ -n "${GITHUB_CLIENT_SECRET:-}" ] && github_state="enabled"
[ -n "${GOOGLE_CLIENT_ID:-}" ] && [ -n "${GOOGLE_CLIENT_SECRET:-}" ] && google_state="enabled"
log "idps: github=${github_state} google=${google_state}"
