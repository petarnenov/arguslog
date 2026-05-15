#!/usr/bin/env bash
#
# Restore smoke — proves scripts/backup-postgres.sh + scripts/restore-postgres.sh wire up
# end-to-end against a *fake* R2 (MinIO) and ephemeral source/target Postgres containers.
#
# What it does:
#   1. Spin up source Postgres + target Postgres + MinIO on a private docker network.
#   2. Seed the source with a synthetic `smoke_data` table (1000 rows).
#   3. Run scripts/backup-postgres.sh AS-IS against the source → MinIO.
#   4. Run scripts/restore-postgres.sh AS-IS against MinIO → target.
#   5. Assert target.smoke_data row count == source.smoke_data row count.
#
# Why not just `pg_dump | pg_restore`? Because the point of this script is to exercise the
# real backup/restore scripts — including their gzip step, their R2 upload, the S3 key
# discovery on restore, and every shell guard rail along the way. If those scripts ever
# regress (renamed env var, broken flag, wrong S3 path), this smoke fails the same way a
# real DR drill would. Run via CI weekly so it can't bitrot.
#
# Usage:
#   bash scripts/restore-smoke.sh
#
# Requires: docker. No host-side pg_dump/aws-cli needed — both are provided by a small
# one-shot tool image built inline from postgres:16-bookworm + awscli.

set -euo pipefail

NETWORK=restore-smoke-net
SOURCE=restore-smoke-source
TARGET=restore-smoke-target
MINIO=restore-smoke-minio
BUCKET=arguslog-backups-smoke
PG_PWD=arguslog
MINIO_KEY=smokekey
MINIO_SECRET=smokesecretsmoke
TOOL_IMAGE=restore-smoke-tools:latest

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

cleanup() {
  echo "→ Cleanup"
  docker rm -f "$SOURCE" "$TARGET" "$MINIO" >/dev/null 2>&1 || true
  docker network rm "$NETWORK" >/dev/null 2>&1 || true
}
trap cleanup EXIT
cleanup  # paranoia: clear leftovers from a prior failed run

echo "→ Building tool image (pg16-client + awscli)"
docker build -q -t "$TOOL_IMAGE" - <<'DOCKERFILE' >/dev/null
FROM postgres:16-bookworm
RUN apt-get update \
 && apt-get install -y --no-install-recommends awscli ca-certificates \
 && rm -rf /var/lib/apt/lists/*
DOCKERFILE

echo "→ Creating private network"
docker network create "$NETWORK" >/dev/null

echo "→ Starting source Postgres"
docker run -d --name "$SOURCE" --network "$NETWORK" \
  -e POSTGRES_USER=arguslog -e POSTGRES_PASSWORD="$PG_PWD" -e POSTGRES_DB=arguslog \
  timescale/timescaledb:latest-pg16 >/dev/null

echo "→ Starting target Postgres"
docker run -d --name "$TARGET" --network "$NETWORK" \
  -e POSTGRES_USER=arguslog -e POSTGRES_PASSWORD="$PG_PWD" -e POSTGRES_DB=arguslog \
  timescale/timescaledb:latest-pg16 >/dev/null

echo "→ Starting MinIO (S3-compatible stand-in for R2)"
docker run -d --name "$MINIO" --network "$NETWORK" \
  -e MINIO_ROOT_USER="$MINIO_KEY" -e MINIO_ROOT_PASSWORD="$MINIO_SECRET" \
  minio/minio server /data >/dev/null

wait_for_pg() {
  local name="$1"
  for _ in $(seq 1 60); do
    if docker exec "$name" pg_isready -U arguslog -d arguslog >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  echo "ERROR: $name never became ready" >&2
  docker logs "$name" >&2 || true
  return 1
}

wait_for_minio() {
  for _ in $(seq 1 30); do
    if docker run --rm --network "$NETWORK" --entrypoint curl curlimages/curl:8.10.1 \
         -fsS -o /dev/null "http://$MINIO:9000/minio/health/live"; then
      return 0
    fi
    sleep 1
  done
  echo "ERROR: minio never became ready" >&2
  docker logs "$MINIO" >&2 || true
  return 1
}

echo "→ Waiting for services"
wait_for_pg "$SOURCE"
wait_for_pg "$TARGET"
wait_for_minio

echo "→ Creating bucket s3://$BUCKET on MinIO"
docker run --rm --network "$NETWORK" \
  -e AWS_ACCESS_KEY_ID="$MINIO_KEY" \
  -e AWS_SECRET_ACCESS_KEY="$MINIO_SECRET" \
  -e AWS_DEFAULT_REGION=us-east-1 \
  amazon/aws-cli --endpoint-url "http://$MINIO:9000" s3 mb "s3://$BUCKET" >/dev/null

echo "→ Seeding source with synthetic smoke_data (1000 rows)"
docker exec -i "$SOURCE" psql -U arguslog -d arguslog -v ON_ERROR_STOP=1 <<'SQL' >/dev/null
CREATE TABLE smoke_data (id int primary key, payload text not null);
INSERT INTO smoke_data SELECT g, 'row-' || g FROM generate_series(1, 1000) g;
SQL

run_in_tools() {
  docker run --rm --network "$NETWORK" \
    -v "$REPO_ROOT":/repo -w /repo \
    -e AWS_ACCESS_KEY_ID="$MINIO_KEY" \
    -e AWS_SECRET_ACCESS_KEY="$MINIO_SECRET" \
    -e AWS_DEFAULT_REGION=us-east-1 \
    "$@"
}

echo "→ Running scripts/backup-postgres.sh against source"
run_in_tools \
  -e DATABASE_URL="postgres://arguslog:arguslog@$SOURCE:5432/arguslog" \
  -e R2_ENDPOINT="http://$MINIO:9000" \
  -e R2_BUCKET="$BUCKET" \
  -e BACKUP_PREFIX=smoke \
  -e ROW_COUNT_SQL="SELECT 'smoke_data', COUNT(*)::text FROM smoke_data" \
  "$TOOL_IMAGE" \
  bash scripts/backup-postgres.sh

echo "→ Discovering the just-uploaded dump"
S3_KEY=$(docker run --rm --network "$NETWORK" \
  -e AWS_ACCESS_KEY_ID="$MINIO_KEY" \
  -e AWS_SECRET_ACCESS_KEY="$MINIO_SECRET" \
  -e AWS_DEFAULT_REGION=us-east-1 \
  amazon/aws-cli --endpoint-url "http://$MINIO:9000" \
    s3api list-objects-v2 --bucket "$BUCKET" --prefix smoke/ --query 'Contents[?ends_with(Key, `dump.gz`)] | sort_by(@, &LastModified)[-1].Key' --output text)

if [[ -z "$S3_KEY" || "$S3_KEY" == "None" ]]; then
  echo "ERROR: backup-postgres.sh appeared to succeed but no dump.gz was uploaded" >&2
  exit 1
fi
echo "  S3_KEY = $S3_KEY"

echo "→ Running scripts/restore-postgres.sh against target"
run_in_tools \
  -e S3_KEY="$S3_KEY" \
  -e TARGET_DATABASE_URL="postgres://arguslog:arguslog@$TARGET:5432/arguslog" \
  -e R2_ENDPOINT="http://$MINIO:9000" \
  -e R2_BUCKET="$BUCKET" \
  -e ALLOW_DESTRUCTIVE=yes \
  "$TOOL_IMAGE" \
  bash scripts/restore-postgres.sh

echo "→ Validating row counts"
COUNT=$(docker exec "$TARGET" psql -U arguslog -d arguslog -At -c "SELECT COUNT(*) FROM smoke_data" | tr -d '[:space:]')
if [[ "$COUNT" != "1000" ]]; then
  echo "ERROR: expected 1000 rows on target.smoke_data, got '$COUNT'" >&2
  exit 1
fi

# Spot-check a row to confirm payload integrity (not just row count parity).
ROW_500=$(docker exec "$TARGET" psql -U arguslog -d arguslog -At -c "SELECT payload FROM smoke_data WHERE id = 500" | tr -d '[:space:]')
if [[ "$ROW_500" != "row-500" ]]; then
  echo "ERROR: row id=500 payload mismatch: got '$ROW_500', want 'row-500'" >&2
  exit 1
fi

echo
echo "✓ Restore smoke passed."
echo "  Source seeded with 1000 rows; backup round-tripped through MinIO; target rebuilt by pg_restore."
