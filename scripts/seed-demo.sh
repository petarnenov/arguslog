#!/usr/bin/env bash
#
# scripts/seed-demo.sh — populate a fresh local stack with a demo user, org,
# project, and a fortnight of synthetic events so the dashboard isn't empty on
# first run.
#
# Idempotent: re-running this is safe — user / org / project creation
# short-circuits on existence, and events are appended (the worker dedupes
# only by fingerprint, so re-running produces extra occurrences of the same
# issues, never new ones).
#
# Requires: curl, jq. Bring up the stack first with `make` in another terminal.

set -u -o pipefail

KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8180}"
API_URL="${API_URL:-http://localhost:8081}"
INGEST_URL="${INGEST_URL:-http://localhost:8080}"
DASHBOARD_URL="${DASHBOARD_URL:-http://localhost:5173}"

REALM="${REALM:-arguslog}"
CLIENT_ID="${CLIENT_ID:-arguslog-web}"
KC_ADMIN_USER="${KC_ADMIN_USER:-admin}"
KC_ADMIN_PASS="${KC_ADMIN_PASS:-admin}"

DEMO_EMAIL="${DEMO_EMAIL:-demo@arguslog.local}"
DEMO_PASSWORD="${DEMO_PASSWORD:-demo}"
DEMO_ORG_NAME="${DEMO_ORG_NAME:-Demo Org}"
DEMO_PROJECT_NAME="${DEMO_PROJECT_NAME:-Demo App}"
DEMO_PROJECT_PLATFORM="${DEMO_PROJECT_PLATFORM:-javascript}"
SYNTHETIC_EVENT_COUNT="${SYNTHETIC_EVENT_COUNT:-12}"

red()    { printf '\033[31m%s\033[0m' "$1"; }
green()  { printf '\033[32m%s\033[0m' "$1"; }
bold()   { printf '\033[1m%s\033[0m' "$1"; }
step()   { printf '\n%s %s\n' "$(bold '▶')" "$1"; }
ok()     { printf '  %s %s\n' "$(green '✓')" "$1"; }
warn()   { printf '  %s %s\n' "$(red '⚠')" "$1"; }
die()    { printf '\n%s %s\n' "$(red '✗')" "$1" >&2; exit 1; }

require_tool() {
  command -v "$1" >/dev/null 2>&1 || die "$1 is required — run 'make doctor' for install instructions."
}

require_tool curl
require_tool jq

# ── 1. wait for API readiness ────────────────────────────────────────────
step "Waiting for api at $API_URL"
for i in $(seq 1 30); do
  if curl -fsS "${API_URL}/actuator/health/readiness" >/dev/null 2>&1; then
    ok "api ready"
    break
  fi
  if [ "$i" -eq 30 ]; then
    die "api never became ready — is 'make' running in another terminal?"
  fi
  sleep 2
done

# ── 2. keycloak admin token ──────────────────────────────────────────────
step "Authenticating against Keycloak ($KEYCLOAK_URL)"
KC_ADMIN_TOKEN="$(curl -fsS -X POST \
  "${KEYCLOAK_URL}/realms/master/protocol/openid-connect/token" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d "username=${KC_ADMIN_USER}" \
  -d "password=${KC_ADMIN_PASS}" \
  -d 'grant_type=password' \
  -d 'client_id=admin-cli' \
  | jq -r '.access_token')" || die "Could not get Keycloak admin token. Is Keycloak up at $KEYCLOAK_URL?"
[ -n "$KC_ADMIN_TOKEN" ] && [ "$KC_ADMIN_TOKEN" != "null" ] || die "Empty admin token response."
ok "admin token acquired"

# ── 3. ensure demo user exists ───────────────────────────────────────────
step "Ensuring demo user ${DEMO_EMAIL}"
EXISTING_USER_ID="$(curl -fsS \
  "${KEYCLOAK_URL}/admin/realms/${REALM}/users?username=${DEMO_EMAIL}&exact=true" \
  -H "Authorization: Bearer ${KC_ADMIN_TOKEN}" \
  | jq -r '.[0].id // empty')"

if [ -n "$EXISTING_USER_ID" ]; then
  ok "user already exists (id=${EXISTING_USER_ID:0:8}…)"
else
  CREATE_RESPONSE_HEADERS="$(mktemp)"
  curl -fsS -D "$CREATE_RESPONSE_HEADERS" -o /dev/null -X POST \
    "${KEYCLOAK_URL}/admin/realms/${REALM}/users" \
    -H "Authorization: Bearer ${KC_ADMIN_TOKEN}" \
    -H 'Content-Type: application/json' \
    -d "$(jq -n \
      --arg email "$DEMO_EMAIL" \
      --arg password "$DEMO_PASSWORD" \
      '{
        username: $email,
        email: $email,
        firstName: "Demo",
        lastName: "User",
        emailVerified: true,
        enabled: true,
        credentials: [{ type: "password", value: $password, temporary: false }]
      }')" || die "Could not create Keycloak user"
  rm -f "$CREATE_RESPONSE_HEADERS"
  ok "user created"
fi

# ── 4. user token via password grant ─────────────────────────────────────
step "Getting demo user JWT (password grant)"
USER_TOKEN_RESPONSE="$(curl -sS -X POST \
  "${KEYCLOAK_URL}/realms/${REALM}/protocol/openid-connect/token" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d "client_id=${CLIENT_ID}" \
  -d "username=${DEMO_EMAIL}" \
  -d "password=${DEMO_PASSWORD}" \
  -d 'grant_type=password')"
USER_TOKEN="$(printf '%s' "$USER_TOKEN_RESPONSE" | jq -r '.access_token // empty')"
if [ -z "$USER_TOKEN" ] || [ "$USER_TOKEN" = "null" ]; then
  printf 'Response: %s\n' "$USER_TOKEN_RESPONSE" >&2
  die "Could not get user token — Direct Access Grants may be disabled for client '${CLIENT_ID}'."
fi
ok "user token acquired"

api_get() {
  curl -fsS "${API_URL}$1" -H "Authorization: Bearer ${USER_TOKEN}"
}
api_post() {
  curl -fsS -X POST "${API_URL}$1" \
    -H "Authorization: Bearer ${USER_TOKEN}" \
    -H 'Content-Type: application/json' \
    -d "$2"
}

# ── 5. ensure demo org exists ────────────────────────────────────────────
step "Ensuring org \"${DEMO_ORG_NAME}\""
ORG_LIST="$(api_get '/api/v1/orgs')"
ORG_ID="$(printf '%s' "$ORG_LIST" \
  | jq -r --arg name "$DEMO_ORG_NAME" 'map(select(.name == $name)) | .[0].id // empty')"

if [ -n "$ORG_ID" ]; then
  ORG_SLUG="$(printf '%s' "$ORG_LIST" \
    | jq -r --arg name "$DEMO_ORG_NAME" 'map(select(.name == $name)) | .[0].slug')"
  ok "org already exists (id=${ORG_ID}, slug=${ORG_SLUG})"
else
  ORG_RESPONSE="$(api_post '/api/v1/orgs' "$(jq -n --arg n "$DEMO_ORG_NAME" '{name: $n}')")"
  ORG_ID="$(printf '%s' "$ORG_RESPONSE" | jq -r '.id')"
  ORG_SLUG="$(printf '%s' "$ORG_RESPONSE" | jq -r '.slug')"
  ok "org created (id=${ORG_ID}, slug=${ORG_SLUG})"
fi

# ── 6. ensure demo project exists ────────────────────────────────────────
step "Ensuring project \"${DEMO_PROJECT_NAME}\""
PROJECT_LIST="$(api_get "/api/v1/orgs/${ORG_ID}/projects")"
PROJECT_ID="$(printf '%s' "$PROJECT_LIST" \
  | jq -r --arg name "$DEMO_PROJECT_NAME" 'map(select(.name == $name)) | .[0].id // empty')"

DSN_PUBLIC=""
if [ -n "$PROJECT_ID" ]; then
  ok "project already exists (id=${PROJECT_ID})"
  # Pick the first active DSN for this project.
  DSN_LIST="$(api_get "/api/v1/projects/${PROJECT_ID}/keys" || echo '[]')"
  DSN_PUBLIC="$(printf '%s' "$DSN_LIST" | jq -r '.[0].dsnPublic // empty')"
  if [ -z "$DSN_PUBLIC" ]; then
    warn "no DSN found for existing project — skipping event seeding"
  fi
else
  PROJECT_RESPONSE="$(api_post "/api/v1/orgs/${ORG_ID}/projects" \
    "$(jq -n --arg n "$DEMO_PROJECT_NAME" --arg p "$DEMO_PROJECT_PLATFORM" \
        '{name: $n, platform: $p}')")"
  PROJECT_ID="$(printf '%s' "$PROJECT_RESPONSE" | jq -r '.project.id')"
  DSN_PUBLIC="$(printf '%s' "$PROJECT_RESPONSE" | jq -r '.dsn.dsnPublic')"
  ok "project created (id=${PROJECT_ID}); DSN minted"
fi

# ── 7. fire synthetic events ─────────────────────────────────────────────
if [ -n "$DSN_PUBLIC" ]; then
  step "Firing ${SYNTHETIC_EVENT_COUNT} synthetic events through ingest"
  LEVELS=("error" "error" "error" "warning" "warning" "info" "fatal")
  MESSAGES=(
    "TypeError: Cannot read property 'name' of undefined"
    "NetworkError: Failed to fetch /api/v1/issues"
    "ReferenceError: foo is not defined"
    "RangeError: Maximum call stack size exceeded"
    "TimeoutError: query exceeded 30000ms"
    "AssertionError: expected 200 got 500"
    "TypeError: x.map is not a function"
  )

  sent=0
  for i in $(seq 1 "$SYNTHETIC_EVENT_COUNT"); do
    LEVEL_IDX=$(( RANDOM % ${#LEVELS[@]} ))
    MSG_IDX=$(( RANDOM % ${#MESSAGES[@]} ))
    # Spread timestamps across the last 14 days for a non-trivial sparkline.
    DAYS_BACK=$(( RANDOM % 14 ))
    if command -v gdate >/dev/null 2>&1; then
      TS=$(gdate -u -d "-${DAYS_BACK} days" +%s)
    elif date -u -v-0d +%s >/dev/null 2>&1; then
      TS=$(date -u -v-"${DAYS_BACK}"d +%s)
    else
      TS=$(date -u +%s)  # fallback: all today
    fi
    EVENT_ID=$(uuidgen 2>/dev/null | tr '[:upper:]' '[:lower:]' \
      || printf '%s-%s-%s-%s-%s' \
        "$(openssl rand -hex 4)" "$(openssl rand -hex 2)" \
        "$(openssl rand -hex 2)" "$(openssl rand -hex 2)" \
        "$(openssl rand -hex 6)")

    PAYLOAD="$(jq -n \
      --arg eid "$EVENT_ID" \
      --arg lvl "${LEVELS[$LEVEL_IDX]}" \
      --arg msg "${MESSAGES[$MSG_IDX]}" \
      --argjson ts "$TS" \
      '{
        eventId: $eid,
        timestamp: $ts,
        platform: "javascript",
        sdk: { name: "@arguslog/seed", version: "1.0.0" },
        level: $lvl,
        message: $msg,
        tags: { env: "demo", source: "seed-script" },
        environment: "demo"
      }')"

    if curl -fsS -X POST "${INGEST_URL}/api/${PROJECT_ID}/events" \
        -H "X-Arguslog-Auth: Arguslog DSN ${DSN_PUBLIC}" \
        -H 'Content-Type: application/json' \
        -d "$PAYLOAD" >/dev/null 2>&1; then
      sent=$((sent + 1))
    fi
  done
  ok "${sent}/${SYNTHETIC_EVENT_COUNT} events accepted by ingest"
fi

# ── 8. banner ────────────────────────────────────────────────────────────
echo ""
echo "$(green '✅ Demo data ready.')"
echo ""
echo "   Dashboard:  $(bold "${DASHBOARD_URL}")"
echo "   Sign in as: $(bold "${DEMO_EMAIL}") / $(bold "${DEMO_PASSWORD}")"
echo "   Project:    ${DEMO_PROJECT_NAME}  (org slug: ${ORG_SLUG}, project id: ${PROJECT_ID})"
echo ""
echo "   Direct link: ${DASHBOARD_URL}/orgs/${ORG_SLUG}/projects/${PROJECT_ID}/issues"
echo ""
