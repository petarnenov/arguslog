#!/usr/bin/env bash
#
# Full-database backup → gzip → R2. Designed to run from a CI cron (GitHub Actions) once per
# day; can also be invoked manually from any machine with pg_dump + aws-cli installed.
#
# The output is a pg_dump custom-format archive (--format=custom) compressed with gzip:
#   • custom format is restorable through pg_restore with table/schema selectivity
#   • smaller than plain SQL (custom is already compressed; gzip squeezes the metadata)
#   • parallel restore supported via `pg_restore -j`
#
# A side artifact records row counts of the highest-value tables (issues, events, users, orgs,
# projects). The numbers in the saved file are what you compare against after a restore to
# confirm the restore got everything — without re-running expensive COUNT(*) on the live DB.
#
# Usage:
#   DATABASE_URL=postgres://user:pass@host:5432/dbname \
#   R2_ENDPOINT=https://<account>.r2.cloudflarestorage.com \
#   R2_BUCKET=arguslog-backups \
#   AWS_ACCESS_KEY_ID=<r2-access-key> \
#   AWS_SECRET_ACCESS_KEY=<r2-secret-key> \
#   bash scripts/backup-postgres.sh
#
# Optional env:
#   BACKUP_PREFIX  default "daily"  — top-level S3 key segment for retention bucketing
#   ROW_COUNT_SQL  override the row-count check (multi-line UNION ALL)

set -euo pipefail

require() {
  local name="$1"
  if [[ -z "${!name:-}" ]]; then
    echo "ERROR: env var $name is required" >&2
    exit 1
  fi
}

require DATABASE_URL
require R2_ENDPOINT
require R2_BUCKET
require AWS_ACCESS_KEY_ID
require AWS_SECRET_ACCESS_KEY

command -v pg_dump >/dev/null || { echo "ERROR: pg_dump not on PATH (install postgresql-client)" >&2; exit 1; }
command -v gzip    >/dev/null || { echo "ERROR: gzip not on PATH" >&2; exit 1; }
command -v aws     >/dev/null || { echo "ERROR: aws CLI not on PATH" >&2; exit 1; }
command -v psql    >/dev/null || { echo "ERROR: psql not on PATH (needed for row-count sanity)" >&2; exit 1; }

BACKUP_PREFIX="${BACKUP_PREFIX:-daily}"
STAMP="$(date -u +%Y%m%d-%H%M%SZ)"
DATE_DIR="$(date -u +%Y-%m-%d)"
TMPDIR="$(mktemp -d)"
trap 'rm -rf "$TMPDIR"' EXIT

DUMP_FILE="$TMPDIR/arguslog-postgres-${STAMP}.dump"
DUMP_GZ="${DUMP_FILE}.gz"

echo "→ Dumping full database (custom format) to $DUMP_FILE"
# --no-owner / --no-privileges so the dump restores cleanly into a database owned by a
# different role (the prod Postgres user differs from a local restore target's role).
pg_dump \
  --format=custom \
  --no-owner \
  --no-privileges \
  --file="$DUMP_FILE" \
  "$DATABASE_URL"

DUMP_BYTES="$(wc -c < "$DUMP_FILE" | tr -d ' ')"
if [[ "$DUMP_BYTES" -lt 1024 ]]; then
  echo "ERROR: dump file is suspiciously small (<1KB) — refusing to upload" >&2
  exit 1
fi
echo "  dump size: $DUMP_BYTES bytes"

echo "→ Compressing"
gzip -9 "$DUMP_FILE"
GZ_BYTES="$(wc -c < "$DUMP_GZ" | tr -d ' ')"
echo "  compressed: $GZ_BYTES bytes"

# Row counts — anchor for post-restore validation. Keep this list tight; we don't need
# alert_rules / destinations / source_map_artifacts here, those are low-cardinality and
# their presence is verified separately during smoke.
ROW_COUNTS="$TMPDIR/row-counts-${STAMP}.txt"
DEFAULT_SQL="
  SELECT 'users',     COUNT(*)::text FROM users
  UNION ALL SELECT 'organizations', COUNT(*)::text FROM organizations
  UNION ALL SELECT 'projects',  COUNT(*)::text FROM projects
  UNION ALL SELECT 'issues',    COUNT(*)::text FROM issues
  UNION ALL SELECT 'events',    COUNT(*)::text FROM events
  UNION ALL SELECT 'releases',  COUNT(*)::text FROM releases
  UNION ALL SELECT 'source_map_artifacts', COUNT(*)::text FROM source_map_artifacts
  UNION ALL SELECT 'admin_audit_log', COUNT(*)::text FROM admin_audit_log;
"
ROW_COUNT_QUERY="${ROW_COUNT_SQL:-$DEFAULT_SQL}"
echo "→ Recording row counts to $ROW_COUNTS"
psql "$DATABASE_URL" -At -c "$ROW_COUNT_QUERY" > "$ROW_COUNTS"
cat "$ROW_COUNTS"

S3_DUMP_KEY="${BACKUP_PREFIX}/${DATE_DIR}/arguslog-postgres-${STAMP}.dump.gz"
S3_COUNTS_KEY="${BACKUP_PREFIX}/${DATE_DIR}/row-counts-${STAMP}.txt"

echo "→ Uploading dump to s3://$R2_BUCKET/$S3_DUMP_KEY"
aws s3 cp "$DUMP_GZ" "s3://$R2_BUCKET/$S3_DUMP_KEY" \
  --endpoint-url "$R2_ENDPOINT"

echo "→ Uploading row counts to s3://$R2_BUCKET/$S3_COUNTS_KEY"
aws s3 cp "$ROW_COUNTS" "s3://$R2_BUCKET/$S3_COUNTS_KEY" \
  --endpoint-url "$R2_ENDPOINT"

echo
echo "✓ Backup complete"
echo "  Dump:        s3://$R2_BUCKET/$S3_DUMP_KEY"
echo "  Row counts:  s3://$R2_BUCKET/$S3_COUNTS_KEY"
