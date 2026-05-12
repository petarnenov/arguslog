#!/usr/bin/env bash
#
# OSS conversion Phase 1 — archival snapshot of billing data before V29+ destructive drops.
#
# Dumps plan_purchases / stripe_events / crypto_invoices from the connected Postgres and
# uploads the compressed dump to R2 (S3-compatible) under arguslog-archive/. Retention on the
# bucket should be configured at the R2 side to keep these for ~7 years (audit / accounting).
#
# This script is meant to be run BY THE OPERATOR (Petar) from his laptop against production
# before merging the Phase 2 migrations that drop the tables. It is NOT wired into CI — the
# pg_dump pulls the entire history of these tables and the credentials needed (DATABASE_URL +
# R2 keys) should not live in CI for this one-shot operation.
#
# Usage:
#   DATABASE_URL=postgres://… \
#   R2_ENDPOINT=https://<account>.r2.cloudflarestorage.com \
#   R2_BUCKET=arguslog-archive \
#   AWS_ACCESS_KEY_ID=<r2-access-key> \
#   AWS_SECRET_ACCESS_KEY=<r2-secret-key> \
#   bash scripts/archive-billing-tables.sh
#
# After success, the script prints the S3 URI of the archive — record it in the Phase 2 PR
# description as the rollback anchor.

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

# Sanity-check tooling.
command -v pg_dump >/dev/null || { echo "ERROR: pg_dump not on PATH (install postgresql-client)" >&2; exit 1; }
command -v gzip >/dev/null    || { echo "ERROR: gzip not on PATH" >&2; exit 1; }
command -v aws >/dev/null     || { echo "ERROR: aws CLI not on PATH" >&2; exit 1; }

STAMP="$(date -u +%Y%m%d-%H%M%SZ)"
TMPDIR="$(mktemp -d)"
trap 'rm -rf "$TMPDIR"' EXIT

DUMP_FILE="$TMPDIR/arguslog-billing-${STAMP}.sql"
DUMP_GZ="${DUMP_FILE}.gz"

echo "→ Dumping plan_purchases, stripe_events, crypto_invoices to $DUMP_FILE"
pg_dump \
  --no-owner \
  --no-privileges \
  --data-only \
  --table=plan_purchases \
  --table=stripe_events \
  --table=crypto_invoices \
  "$DATABASE_URL" \
  > "$DUMP_FILE"

# Quick sanity: dump must contain at least the COPY headers for each table or it's empty.
for table in plan_purchases stripe_events crypto_invoices; do
  if ! grep -q "^COPY public.$table" "$DUMP_FILE"; then
    echo "WARNING: dump has no COPY block for $table — table may not exist or is empty"
  fi
done

echo "→ Compressing"
gzip -9 "$DUMP_FILE"

ROW_COUNTS="$TMPDIR/row-counts-${STAMP}.txt"
echo "→ Recording row counts to $ROW_COUNTS"
psql "$DATABASE_URL" -At -c \
  "SELECT 'plan_purchases', COUNT(*) FROM plan_purchases
   UNION ALL SELECT 'stripe_events', COUNT(*) FROM stripe_events
   UNION ALL SELECT 'crypto_invoices', COUNT(*) FROM crypto_invoices;" \
  > "$ROW_COUNTS"
cat "$ROW_COUNTS"

S3_DUMP_KEY="billing/${STAMP}/arguslog-billing.sql.gz"
S3_COUNTS_KEY="billing/${STAMP}/row-counts.txt"

echo "→ Uploading dump to s3://$R2_BUCKET/$S3_DUMP_KEY"
aws s3 cp "$DUMP_GZ" "s3://$R2_BUCKET/$S3_DUMP_KEY" \
  --endpoint-url "$R2_ENDPOINT"

echo "→ Uploading row counts to s3://$R2_BUCKET/$S3_COUNTS_KEY"
aws s3 cp "$ROW_COUNTS" "s3://$R2_BUCKET/$S3_COUNTS_KEY" \
  --endpoint-url "$R2_ENDPOINT"

echo
echo "✓ Archive complete"
echo "  Dump:        s3://$R2_BUCKET/$S3_DUMP_KEY"
echo "  Row counts:  s3://$R2_BUCKET/$S3_COUNTS_KEY"
echo
echo "Record the above two URIs in the Phase 2 PR description before merging the V29+ drops."
