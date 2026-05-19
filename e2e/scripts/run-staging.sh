#!/usr/bin/env bash
#
# scripts/run-staging.sh — wrapper that pnpm test:staging-headless calls.
#
# Targets the live staging environment (arguslog-*-staging.up.railway.app). Reads
# the runner PAT from e2e/.staging-runner-pat by default; export
# ARGUSLOG_E2E_RUNNER_PAT to override (CI workflows do this with the GH secret).
#
# Defaults to `--workers=1` because the demo user on staging is on the `regular`
# tier (1-org cap). Higher parallelism would hit 402 PaymentRequired on the
# second concurrent `seededOrg` create. To raise it, the operator must promote
# the runner user via the admin API or DB.

set -u -o pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
E2E_DIR="$(cd -- "$HERE/.." && pwd)"
PAT_FILE="${E2E_DIR}/.staging-runner-pat"

red()  { printf '\033[31m%s\033[0m' "$1"; }
bold() { printf '\033[1m%s\033[0m' "$1"; }

if [ -z "${ARGUSLOG_E2E_RUNNER_PAT:-}" ]; then
  if [ -r "$PAT_FILE" ]; then
    ARGUSLOG_E2E_RUNNER_PAT="$(cat "$PAT_FILE")"
  else
    printf '\n%s No staging runner PAT available.\n\n' "$(red '✗')" >&2
    printf '  Either:\n' >&2
    printf '    1) Mint a PAT at %s,\n' "$(bold 'https://arguslog-web-staging.up.railway.app/me/tokens')" >&2
    printf '       save the plaintext to %s, OR\n' "$(bold "$PAT_FILE")" >&2
    printf '    2) Export %s directly.\n\n' "$(bold 'ARGUSLOG_E2E_RUNNER_PAT')" >&2
    exit 1
  fi
fi

ARGUSLOG_E2E_RUNNER_PAT="${ARGUSLOG_E2E_RUNNER_PAT%$'\n'}"
export ARGUSLOG_E2E_RUNNER_PAT

# Staging endpoints. Note: landing has no staging deployment — `arguslog.org` is
# the prod marketing site, used read-only by landing specs (no mutations).
export ARGUSLOG_E2E_BASE_URL="${ARGUSLOG_E2E_BASE_URL:-https://arguslog-web-staging.up.railway.app}"
export ARGUSLOG_E2E_LANDING_URL="${ARGUSLOG_E2E_LANDING_URL:-https://arguslog.org}"
export ARGUSLOG_E2E_API_URL="${ARGUSLOG_E2E_API_URL:-https://arguslog-api-staging.up.railway.app}"
export ARGUSLOG_E2E_INGEST_URL="${ARGUSLOG_E2E_INGEST_URL:-https://arguslog-ingest-staging.up.railway.app}"
export ARGUSLOG_E2E_KEYCLOAK_URL="${ARGUSLOG_E2E_KEYCLOAK_URL:-https://arguslog-keycloak-staging.up.railway.app}"

# Default to --workers=1 (regular tier 1-org cap) unless caller overrides.
WORKERS_FLAG=()
case " $* " in
  *' --workers='*|*' --workers '*) ;;
  *) WORKERS_FLAG=(--workers=1) ;;
esac

cd "$E2E_DIR"
exec ./node_modules/.bin/playwright test "${WORKERS_FLAG[@]}" "$@"
