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
# Dedicated dev-only client with Direct Access Grants enabled (see realm.template.json).
# The main `arguslog-web` client keeps DAG off for production safety.
CLIENT_ID="${CLIENT_ID:-arguslog-seed}"
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

# ── 1b. local-only: relax master-realm sslRequired ───────────────────────
# Docker bridge networking makes host→localhost:8180 requests look "external"
# to Keycloak, so admin auth on the master realm (default sslRequired=external)
# gets a 426 "HTTPS required" before any password is ever evaluated. We patch
# master to sslRequired=NONE via `docker exec` — that originates from the
# container's loopback interface, which is always exempt regardless of the
# master realm's current setting.
#
# Strictly gated on KEYCLOAK_URL starting with http:// — staging and prod KC
# instances are hit over HTTPS and their master realm MUST keep `external`.
# The `docker exec` provides a second layer: it silently fails on machines
# where `arguslog-keycloak` is not a local container (i.e. anyone running this
# script against a remote KC).
if [[ "$KEYCLOAK_URL" == http://* ]]; then
  if docker exec arguslog-keycloak /opt/keycloak/bin/kcadm.sh config credentials \
       --server http://localhost:8180 --realm master \
       --user "${KC_ADMIN_USER}" --password "${KC_ADMIN_PASS}" >/dev/null 2>&1; then
    docker exec arguslog-keycloak /opt/keycloak/bin/kcadm.sh \
      update realms/master -s sslRequired=NONE >/dev/null 2>&1 || true
  fi
fi

# ── 2. keycloak admin token ──────────────────────────────────────────────
step "Authenticating against Keycloak ($KEYCLOAK_URL)"
KC_ADMIN_TOKEN_RAW="$(curl -sS -X POST \
  "${KEYCLOAK_URL}/realms/master/protocol/openid-connect/token" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d "username=${KC_ADMIN_USER}" \
  -d "password=${KC_ADMIN_PASS}" \
  -d 'grant_type=password' \
  -d 'client_id=admin-cli')"
KC_ADMIN_TOKEN="$(printf '%s' "$KC_ADMIN_TOKEN_RAW" | jq -r '.access_token // empty')"
if [ -z "$KC_ADMIN_TOKEN" ] || [ "$KC_ADMIN_TOKEN" = "null" ]; then
  printf '\nResponse: %s\n\n' "$KC_ADMIN_TOKEN_RAW" >&2
  echo "Keycloak admin auth (${KC_ADMIN_USER}/${KC_ADMIN_PASS}) failed. Likely causes:" >&2
  echo "  • Postgres volume predates the KC_BOOTSTRAP_ADMIN_* env vars in docker-compose," >&2
  echo "    so the bootstrap admin was never created at first boot." >&2
  echo "  • Your local KC admin password was changed via the UI." >&2
  echo "" >&2
  echo "Fixes:" >&2
  echo "  1) Run 'make fresh && make' to wipe Keycloak state and recreate admin/admin." >&2
  echo "  2) Or export KC_ADMIN_USER=<u> KC_ADMIN_PASS=<p> before re-running this." >&2
  die "Cannot continue without admin token."
fi
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
  printf '\nResponse: %s\n\n' "$USER_TOKEN_RESPONSE" >&2
  echo "Could not get a user token through client '${CLIENT_ID}'." >&2
  echo "" >&2
  echo "Most likely: your imported Keycloak realm predates the dev-only" >&2
  echo "'arguslog-seed' client (added to realm.template.json). The running KC" >&2
  echo "is still on the old realm — realm imports only happen on first boot." >&2
  echo "" >&2
  echo "Fix: run 'make fresh && make' to drop the Postgres volume and re-import" >&2
  echo "      the realm with the new seed client." >&2
  die "Cannot continue without user token."
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

# ── 7c. local-only: mint runner PAT and persist plaintext for e2e ────────
# `pnpm test:dev` reads this file to authenticate the test runner. We rotate it
# on every seed so the file content is always fresh — same scheme-gate as 1b/7b.
if [[ "$KEYCLOAK_URL" == http://* ]]; then
  step "Minting e2e runner PAT (local-only)"
  RUNNER_PAT_NAME="${RUNNER_PAT_NAME:-e2e-runner-local}"
  # Delete any prior PAT with this name so the token list doesn't accumulate
  # one extra row per seed run. 404 is fine — first run has nothing to clean.
  EXISTING_PAT_IDS="$(curl -fsS "${API_URL}/api/v1/me/tokens" \
    -H "Authorization: Bearer ${USER_TOKEN}" \
    | jq -r --arg n "$RUNNER_PAT_NAME" 'map(select(.name == $n)) | .[].id' || true)"
  for tid in $EXISTING_PAT_IDS; do
    curl -fsS -X DELETE "${API_URL}/api/v1/me/tokens/${tid}" \
      -H "Authorization: Bearer ${USER_TOKEN}" >/dev/null 2>&1 || true
  done
  # The mint endpoint returns the plaintext under `.token` (the only call that ever
  # surfaces it — subsequent reads only see the prefix).
  RUNNER_PAT_PLAINTEXT="$(curl -fsS -X POST "${API_URL}/api/v1/me/tokens" \
    -H "Authorization: Bearer ${USER_TOKEN}" \
    -H 'Content-Type: application/json' \
    -d "$(jq -n --arg n "$RUNNER_PAT_NAME" '{name: $n}')" \
    | jq -r '.token // empty')"
  if [ -n "$RUNNER_PAT_PLAINTEXT" ]; then
    # Resolve repo root so this works regardless of caller CWD. The script lives
    # in scripts/ — go one level up.
    REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
    PAT_FILE="${REPO_ROOT}/e2e/.local-runner-pat"
    printf '%s' "$RUNNER_PAT_PLAINTEXT" > "$PAT_FILE"
    chmod 600 "$PAT_FILE"
    ok "PAT written to e2e/.local-runner-pat"
  else
    warn "could not mint runner PAT — e2e suite will need ARGUSLOG_E2E_RUNNER_PAT set manually"
  fi
fi

# ── 7b. local-only: grant demo user platinum tier ───────────────────────
# The regular tier caps users at 1 organization — which is enough for the seeded
# Demo Org, but the e2e suite (`pnpm test:dev`) creates many `e2e-*` orgs in
# parallel and would hit 402 PaymentRequired on every second org. Promoting the
# demo user to platinum (effectively no cap) lets the suite run unchanged.
#
# Same scheme-gate as step 1b: only apply when KEYCLOAK_URL is http://, which is
# our shorthand for "running against a local Docker stack". Staging/prod tiers
# are managed manually by a platform admin — we never want this script to
# silently elevate privileges on a remote env.
if [[ "$KEYCLOAK_URL" == http://* ]]; then
  step "Granting platinum tier to demo user (local-only, idempotent)"
  if docker exec arguslog-postgres bash -c \
       "psql -U \$POSTGRES_USER -d \$POSTGRES_DB -tAc \"UPDATE users SET tier='platinum' WHERE email='${DEMO_EMAIL}' AND tier != 'platinum'\"" \
       >/dev/null 2>&1; then
    ok "demo user is platinum"
  else
    warn "could not grant platinum (postgres container missing?); e2e suite may hit org caps"
  fi
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
