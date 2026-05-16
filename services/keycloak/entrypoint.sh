#!/usr/bin/env bash
# Keycloak container entrypoint that:
#   1. Starts kc.sh in the background.
#   2. Runs configure-idps.sh against localhost:8080 to upsert social-login
#      identity providers from env vars (idempotent on every boot).
#   3. wait's on the Keycloak PID so the container's lifecycle == kc.sh's.
#
# Background-execing kc.sh + foregrounding the patcher would have Railway
# mark the container "started" before configuration completed; that's why
# we keep kc.sh in the foreground (via wait) and run the patcher as a
# detached coroutine that exits when done.

set -u -o pipefail

# Run the patcher in the background — it polls /health/ready and exits on
# its own once configuration lands.
( /opt/keycloak/configure-idps.sh ) &

# Start Keycloak in the foreground. exec replaces this shell so signals
# (SIGTERM from `docker stop`) reach kc.sh directly without the parent
# absorbing them.
exec /opt/keycloak/bin/kc.sh start --optimized --import-realm
