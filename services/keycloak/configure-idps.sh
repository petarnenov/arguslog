#!/usr/bin/env bash
# Post-boot Keycloak Admin-API patcher for social-login identity providers.
#
# Runs from the Dockerfile entrypoint AFTER kc.sh start brings the server up. Reads six env
# vars (GITHUB_CLIENT_ID, GITHUB_CLIENT_SECRET, GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET,
# GITLAB_CLIENT_ID, GITLAB_CLIENT_SECRET) and upserts or deletes the matching IdP via
# /opt/keycloak/bin/kcadm.sh.
#
# Why kcadm.sh and not curl+jq: Keycloak 26.x dropped the UBI-minimal base in favor of a
# slimmer image that ships no package manager (no microdnf, no dnf). We can't install
# curl/jq at build time. kcadm.sh is bundled with the image, knows the Admin REST API
# natively, and accepts `-s key=value` flags so we don't need JSON construction either.
#
# Idempotent — safe to run on every container start. Realm import (--import-realm) only
# happens on first boot, so this script is the authoritative way to keep long-running prod
# realms in sync with the env-var-supplied credentials.

set -u -o pipefail

KC_URL="${KC_URL:-http://localhost:8080}"
REALM="${REALM:-arguslog}"
ADMIN_USER="${KC_BOOTSTRAP_ADMIN_USERNAME:-${KEYCLOAK_ADMIN:-admin}}"
ADMIN_PASS="${KC_BOOTSTRAP_ADMIN_PASSWORD:-${KEYCLOAK_ADMIN_PASSWORD:-admin}}"
KCADM=/opt/keycloak/bin/kcadm.sh

log() { printf '[configure-idps] %s\n' "$*"; }

# ── 1. wait for the HTTP port to listen ─────────────────────────────────
# kcadm.sh is a Java CLI — ~3-5s JVM cold start per invocation. We DON'T retry it in a loop
# for readiness; instead we poll the TCP port (cheap, instant) and only call kcadm once KC
# is actually listening.
log "waiting for Keycloak HTTP port on 8080"
ready=0
for i in $(seq 1 120); do
  if exec 3<>/dev/tcp/127.0.0.1/8080 2>/dev/null; then
    exec 3<&-
    log "KC port 8080 listening after ${i}s"
    ready=1
    break
  fi
  sleep 1
done
if [ "$ready" -ne 1 ]; then
  log "FATAL: KC port 8080 not listening in 120s — bailing (KC will still serve email/password)"
  exit 0
fi

# ── 2. authenticate kcadm against the master realm ──────────────────────
if ! "$KCADM" config credentials --server "$KC_URL" --realm master \
       --user "$ADMIN_USER" --password "$ADMIN_PASS" >/dev/null 2>&1; then
  log "could not authenticate as ${ADMIN_USER}; skipping IdP configuration"
  log "(KC still serves email/password login — admin credentials may need a rotation)"
  exit 0
fi

# ── 3. ensure the `auto-link` flow exists with two ALTERNATIVE executions ──
ensure_auto_link_flow() {
  if "$KCADM" get "authentication/flows" -r "$REALM" 2>/dev/null \
       | grep -q '"alias" *: *"auto-link"'; then
    log "flow 'auto-link' already exists"
    return 0
  fi

  log "creating auto-link flow"
  if ! "$KCADM" create "authentication/flows" -r "$REALM" \
         -s alias=auto-link \
         -s 'description=First-broker-login flow that silently links a fresh IdP login to an existing email-matched user — no consent screen.' \
         -s providerId=basic-flow \
         -s topLevel=true \
         -s builtIn=false >/dev/null 2>&1; then
    log "flow create failed; continuing without auto-link"
    return 0
  fi

  for authn in idp-create-user-if-unique idp-auto-link; do
    "$KCADM" create "authentication/flows/auto-link/executions/execution" -r "$REALM" \
      -s "provider=$authn" >/dev/null 2>&1 || log "execution ${authn} add returned non-zero"
  done

  # Flip both executions to ALTERNATIVE. kcadm needs the execution id from the listing.
  # We grep it out without jq — the IDs are UUIDs that occupy a stable position in the
  # output line, and the script does not depend on the order beyond „touch every execution".
  "$KCADM" get "authentication/flows/auto-link/executions" -r "$REALM" 2>/dev/null \
    | grep -oE '"id" *: *"[a-f0-9-]+"' | cut -d'"' -f4 \
    | while read -r exec_id; do
        "$KCADM" update "authentication/flows/auto-link/executions" -r "$REALM" \
          -b "{\"id\":\"${exec_id}\",\"requirement\":\"ALTERNATIVE\"}" >/dev/null 2>&1 \
            || log "could not set ALTERNATIVE on execution ${exec_id}"
      done
  log "flow 'auto-link' created with 2 executions"
}

# ── 4. upsert / delete each IdP from env-supplied credentials ───────────
upsert_idp() {
  local alias="$1" provider_id="$2" client_id="$3" client_secret="$4" scope="$5"
  if [ -z "$client_id" ] || [ -z "$client_secret" ]; then
    log "${alias}: no credentials in env; ensuring instance is removed"
    "$KCADM" delete "identity-provider/instances/${alias}" -r "$REALM" >/dev/null 2>&1 || true
    return 0
  fi

  if "$KCADM" get "identity-provider/instances/${alias}" -r "$REALM" >/dev/null 2>&1; then
    if "$KCADM" update "identity-provider/instances/${alias}" -r "$REALM" \
         -s enabled=true \
         -s trustEmail=true \
         -s storeToken=false \
         -s linkOnly=false \
         -s firstBrokerLoginFlowAlias=auto-link \
         -s "config.clientId=${client_id}" \
         -s "config.clientSecret=${client_secret}" \
         -s config.syncMode=IMPORT \
         -s "config.defaultScope=${scope}" >/dev/null 2>&1; then
      log "${alias}: updated"
    else
      log "${alias}: update failed; check KC logs"
    fi
  else
    if "$KCADM" create "identity-provider/instances" -r "$REALM" \
         -s "alias=${alias}" \
         -s "providerId=${provider_id}" \
         -s enabled=true \
         -s trustEmail=true \
         -s storeToken=false \
         -s linkOnly=false \
         -s firstBrokerLoginFlowAlias=auto-link \
         -s "config.clientId=${client_id}" \
         -s "config.clientSecret=${client_secret}" \
         -s config.syncMode=IMPORT \
         -s "config.defaultScope=${scope}" >/dev/null 2>&1; then
      log "${alias}: created"
    else
      log "${alias}: create failed; check KC logs"
    fi
  fi
}

ensure_auto_link_flow

upsert_idp "github" "github" \
  "${GITHUB_CLIENT_ID:-}" "${GITHUB_CLIENT_SECRET:-}" \
  "user:email read:user"

upsert_idp "google" "google" \
  "${GOOGLE_CLIENT_ID:-}" "${GOOGLE_CLIENT_SECRET:-}" \
  "openid email profile"

upsert_idp "gitlab" "gitlab" \
  "${GITLAB_CLIENT_ID:-}" "${GITLAB_CLIENT_SECRET:-}" \
  "openid email profile read_user"

github_state="disabled"; google_state="disabled"; gitlab_state="disabled"
[ -n "${GITHUB_CLIENT_ID:-}" ] && [ -n "${GITHUB_CLIENT_SECRET:-}" ] && github_state="enabled"
[ -n "${GOOGLE_CLIENT_ID:-}" ] && [ -n "${GOOGLE_CLIENT_SECRET:-}" ] && google_state="enabled"
[ -n "${GITLAB_CLIENT_ID:-}" ] && [ -n "${GITLAB_CLIENT_SECRET:-}" ] && gitlab_state="enabled"
log "idps: github=${github_state} google=${google_state} gitlab=${gitlab_state}"
