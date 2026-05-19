#!/usr/bin/env bash
#
# scripts/run-local.sh — wrapper that pnpm test:dev calls.
#
# Auto-reads the runner PAT from e2e/.local-runner-pat (written by
# scripts/seed-demo.sh during `make seed` / `make demo`) so the user doesn't
# have to remember to export ARGUSLOG_E2E_RUNNER_PAT each time.
#
# Defaults to `--workers=2` because the demo user's platinum tier allows
# parallel org creation but the api's rate-limiter dislikes higher concurrency.
# Pass `--workers=N` after the npm-script name to override.
#
# Forwards any extra argv to playwright, so `pnpm test:dev --grep landing` or
# `pnpm test:dev tests/dashboard/foo.spec.ts` Just Work.

set -u -o pipefail

# Resolve script-relative paths so the wrapper works from any CWD.
HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
E2E_DIR="$(cd -- "$HERE/.." && pwd)"
PAT_FILE="${E2E_DIR}/.local-runner-pat"

red()   { printf '\033[31m%s\033[0m' "$1"; }
bold()  { printf '\033[1m%s\033[0m' "$1"; }

if [ -z "${ARGUSLOG_E2E_RUNNER_PAT:-}" ]; then
  if [ -r "$PAT_FILE" ]; then
    ARGUSLOG_E2E_RUNNER_PAT="$(cat "$PAT_FILE")"
  else
    printf '\n%s No runner PAT available.\n\n' "$(red '✗')" >&2
    printf '  The local PAT file %s is missing. Bring the stack up and seed it:\n\n' "$(bold "$PAT_FILE")" >&2
    printf '    %s   # boots infra + dev stack + auto-seeds (mints PAT, grants platinum)\n' "$(bold 'make demo')" >&2
    printf '    %s        # standalone re-seed if the stack is already up\n\n' "$(bold 'make seed')" >&2
    printf '  Or export ARGUSLOG_E2E_RUNNER_PAT directly to bypass the file:\n\n' >&2
    printf '    %s\n\n' "$(bold 'export ARGUSLOG_E2E_RUNNER_PAT=arglog_pat_...')" >&2
    exit 1
  fi
fi

# Trim trailing newline (printf '%s' wouldn't add one; cat preserves whatever's there).
ARGUSLOG_E2E_RUNNER_PAT="${ARGUSLOG_E2E_RUNNER_PAT%$'\n'}"
export ARGUSLOG_E2E_RUNNER_PAT

# Local stack endpoints. All overridable via env if the user runs the local web
# on a non-default port or proxies through a tunnel.
export ARGUSLOG_E2E_BASE_URL="${ARGUSLOG_E2E_BASE_URL:-http://localhost:5173}"
export ARGUSLOG_E2E_LANDING_URL="${ARGUSLOG_E2E_LANDING_URL:-http://localhost:5174}"
export ARGUSLOG_E2E_API_URL="${ARGUSLOG_E2E_API_URL:-http://localhost:8081}"
export ARGUSLOG_E2E_INGEST_URL="${ARGUSLOG_E2E_INGEST_URL:-http://localhost:8080}"
export ARGUSLOG_E2E_KEYCLOAK_URL="${ARGUSLOG_E2E_KEYCLOAK_URL:-http://localhost:8180}"

# Default to --workers=2 unless the caller already specified a workers flag.
WORKERS_FLAG=()
case " $* " in
  *' --workers='*|*' --workers '*) ;;  # caller overrode
  *) WORKERS_FLAG=(--workers=2) ;;
esac

cd "$E2E_DIR"
exec ./node_modules/.bin/playwright test "${WORKERS_FLAG[@]}" "$@"
