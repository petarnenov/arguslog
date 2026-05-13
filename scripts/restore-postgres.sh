#!/usr/bin/env bash
#
# Restores a Postgres dump produced by backup-postgres.sh into TARGET_DATABASE_URL.
#
# This is a DESTRUCTIVE operation — pg_restore will drop and recreate every object the dump
# contains in the target schema. We refuse to run unless the operator explicitly opts in via
# ALLOW_DESTRUCTIVE=yes, and we ALWAYS refuse to run against the same URL as DATABASE_URL when
# both are set (so a stray invocation can't clobber prod from a script that thinks it's
# pointing at staging).
#
# Usage:
#   S3_KEY=daily/2026-05-13/arguslog-postgres-20260513-040000Z.dump.gz \
#   TARGET_DATABASE_URL=postgres://user:pass@localhost:5432/arguslog_restore \
#   R2_ENDPOINT=https://<account>.r2.cloudflarestorage.com \
#   R2_BUCKET=arguslog-backups \
#   AWS_ACCESS_KEY_ID=<r2-access-key> \
#   AWS_SECRET_ACCESS_KEY=<r2-secret-key> \
#   ALLOW_DESTRUCTIVE=yes \
#   bash scripts/restore-postgres.sh
#
# Optional env:
#   PARALLEL_JOBS  default 4 — pg_restore -j worker count

set -euo pipefail

require() {
  local name="$1"
  if [[ -z "${!name:-}" ]]; then
    echo "ERROR: env var $name is required" >&2
    exit 1
  fi
}

require S3_KEY
require TARGET_DATABASE_URL
require R2_ENDPOINT
require R2_BUCKET
require AWS_ACCESS_KEY_ID
require AWS_SECRET_ACCESS_KEY

if [[ "${ALLOW_DESTRUCTIVE:-}" != "yes" ]]; then
  echo "ERROR: ALLOW_DESTRUCTIVE=yes must be set explicitly — refusing to overwrite a database" >&2
  exit 1
fi

# Belt-and-braces: refuse to restore on top of the source DB.
if [[ -n "${DATABASE_URL:-}" && "$DATABASE_URL" == "$TARGET_DATABASE_URL" ]]; then
  echo "ERROR: TARGET_DATABASE_URL equals DATABASE_URL — refusing to restore onto the source DB" >&2
  exit 1
fi

command -v pg_restore >/dev/null || { echo "ERROR: pg_restore not on PATH (install postgresql-client)" >&2; exit 1; }
command -v gunzip     >/dev/null || { echo "ERROR: gunzip not on PATH" >&2; exit 1; }
command -v aws        >/dev/null || { echo "ERROR: aws CLI not on PATH" >&2; exit 1; }

PARALLEL_JOBS="${PARALLEL_JOBS:-4}"
TMPDIR="$(mktemp -d)"
trap 'rm -rf "$TMPDIR"' EXIT

DUMP_GZ="$TMPDIR/snapshot.dump.gz"
DUMP_FILE="$TMPDIR/snapshot.dump"

echo "→ Downloading s3://$R2_BUCKET/$S3_KEY"
aws s3 cp "s3://$R2_BUCKET/$S3_KEY" "$DUMP_GZ" \
  --endpoint-url "$R2_ENDPOINT"

DOWNLOAD_BYTES="$(wc -c < "$DUMP_GZ" | tr -d ' ')"
if [[ "$DOWNLOAD_BYTES" -lt 1024 ]]; then
  echo "ERROR: downloaded dump is suspiciously small (<1KB)" >&2
  exit 1
fi
echo "  size: $DOWNLOAD_BYTES bytes"

echo "→ Decompressing"
gunzip "$DUMP_GZ"

echo "→ Restoring into $TARGET_DATABASE_URL (parallel=$PARALLEL_JOBS, clean+create)"
# --clean   drop existing objects before recreate
# --if-exists  no error when an object isn't there yet
# --no-owner / --no-privileges  same as the dump, for cross-environment portability
pg_restore \
  --no-owner \
  --no-privileges \
  --clean \
  --if-exists \
  --jobs="$PARALLEL_JOBS" \
  --dbname="$TARGET_DATABASE_URL" \
  "$DUMP_FILE"

echo
echo "✓ Restore complete."
echo "  Cross-check with backup-time row counts (same S3 prefix, row-counts-*.txt) to confirm parity."
