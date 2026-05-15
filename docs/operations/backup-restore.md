# Postgres backups + restore

## TL;DR

- A GitHub Actions workflow runs `scripts/backup-postgres.sh` every day at 04:00 UTC.
- Each run uploads a gzip-compressed pg_dump custom-format archive to Cloudflare R2 under
  `s3://arguslog-backups/daily/<YYYY-MM-DD>/arguslog-postgres-<timestamp>.dump.gz`, plus a
  small `row-counts-*.txt` next to it for post-restore validation.
- Retention is enforced on R2 with a bucket lifecycle rule (30 days) — the workflow itself
  never deletes objects.
- To restore: `scripts/restore-postgres.sh` downloads any snapshot by S3 key and pipes it
  through `pg_restore` into a target database.
- The end-to-end shape of both scripts is exercised every Monday by
  `.github/workflows/restore-smoke.yml` against a MinIO stand-in. Locally, run
  `bash scripts/restore-smoke.sh` for the same round-trip in Docker.

---

## One-time setup

### 1. Create the R2 bucket

In Cloudflare → R2:

1. Create a new bucket named `arguslog-backups` (or pick any name; update the workflow secret
   to match).
2. **Settings → Object Lifecycle Rules → Add rule**:
   - Match: prefix `daily/`
   - Action: **Delete objects** after 30 days
3. Create an R2 API token scoped to this bucket only (write + read). Copy the access key ID
   and secret — you'll paste them into GitHub secrets in the next step.

> The lifecycle rule is what enforces retention. The backup workflow never prunes, on
> purpose: a misconfigured workflow can't accidentally wipe history.

### 2. Add the GitHub secrets

Repo → **Settings → Secrets and variables → Actions → New repository secret**. Five secrets:

| Secret              | Value                                                            |
| ------------------- | ---------------------------------------------------------------- |
| `PROD_DATABASE_URL` | libpq URL for production Postgres (`postgres://user:pass@host/db`) |
| `R2_BACKUP_ENDPOINT`| `https://<account>.r2.cloudflarestorage.com`                     |
| `R2_BACKUP_BUCKET`  | `arguslog-backups` (or whatever you named the bucket above)      |
| `R2_BACKUP_KEY_ID`  | R2 access key ID for the bucket-scoped token                     |
| `R2_BACKUP_SECRET`  | R2 secret access key for the bucket-scoped token                 |

> **Why a libpq URL and not the existing Railway-style `jdbc:postgresql://`?** Because
> `pg_dump` uses libpq, not JDBC. Convert by stripping `jdbc:` prefix and folding credentials
> into the URL: `postgres://username:password@host:port/dbname`.

### 3. Verify

Trigger the workflow manually once and confirm an object lands in R2:

- GitHub → Actions → **Backup Postgres** → **Run workflow** → leave `backup_prefix=manual`.
- Wait ~2–5 min.
- Check R2: `s3://arguslog-backups/manual/<today>/arguslog-postgres-*.dump.gz` should exist.
- Read `row-counts-*.txt` and confirm the numbers look right.

---

## Restoring

Restores are destructive — the target database is wiped and rebuilt. The script refuses to
run without `ALLOW_DESTRUCTIVE=yes` and refuses to restore on top of the source DB.

### Local restore (development / drill / staging recovery)

```bash
# 1. Spin up a throwaway Postgres
docker run -d --name arguslog-restore -p 55432:5432 \
  -e POSTGRES_USER=arguslog -e POSTGRES_PASSWORD=arguslog -e POSTGRES_DB=arguslog \
  timescale/timescaledb:latest-pg16

# 2. Restore from R2
export S3_KEY="daily/2026-05-13/arguslog-postgres-20260513-040000Z.dump.gz"
export TARGET_DATABASE_URL="postgres://arguslog:arguslog@localhost:55432/arguslog"
export R2_ENDPOINT="https://<account>.r2.cloudflarestorage.com"
export R2_BUCKET="arguslog-backups"
export AWS_ACCESS_KEY_ID="<r2-access-key>"
export AWS_SECRET_ACCESS_KEY="<r2-secret-key>"
export ALLOW_DESTRUCTIVE=yes

bash scripts/restore-postgres.sh

# 3. Sanity check — compare against the row-counts file from the same snapshot
psql "$TARGET_DATABASE_URL" -At -c "
  SELECT 'users', COUNT(*) FROM users
  UNION ALL SELECT 'organizations', COUNT(*) FROM organizations
  UNION ALL SELECT 'projects', COUNT(*) FROM projects
  UNION ALL SELECT 'issues', COUNT(*) FROM issues
  UNION ALL SELECT 'events', COUNT(*) FROM events;"
```

### Production disaster recovery

Same flow, but `TARGET_DATABASE_URL` points at a brand-new Postgres instance you provisioned
to take over from the broken one. Steps:

1. Create a fresh Postgres on Railway (or wherever) at the same major version (16).
2. Set `TARGET_DATABASE_URL` to its connection string.
3. Run `scripts/restore-postgres.sh` with the latest snapshot.
4. Update each Railway app service's `DATABASE_URL` to the new host.
5. Trigger `railway redeploy` for `arguslog-api` + `arguslog-worker` + `arguslog-ingest`.
6. Validate: visit the dashboard, confirm issues + recent events are present; ingest a test
   event with the SDK and confirm it lands.

**RPO**: up to 24 hours (worst case — snapshot from yesterday's 04:00 UTC).
**RTO**: ~10 min for restore + redeploy on a small-ish DB. Scales with row count.

---

## Operational notes

- **TimescaleDB hypertables** (`events`) are dumped by pg_dump like regular tables. Restore
  re-creates them as plain tables; TimescaleDB metadata won't follow. If we ever need to
  retain hypertable chunk policies across a restore, switch to `pg_dump --format=directory
  -j` + `pg_restore` plus TimescaleDB's `timescaledb-backup` extension. Not needed for MVP
  scale.
- **Schema-only debugging**: pass `pg_restore --schema-only` if you only want the DDL.
- **Selective restore**: `pg_restore --table=issues` works because we use custom format.
- **Encryption**: Cloudflare R2 buckets default to server-side encryption with managed keys
  (SSE-S3). For higher-paranoia setups, layer client-side `gpg --symmetric` between the
  `pg_dump` and the `aws s3 cp` step in the script. Restore must reverse with `gpg -d`.

## Triggering ad-hoc backups

Before any irreversible operation (migrations, table drops, mass updates), take a manual
snapshot:

- GitHub Actions → **Backup Postgres** → **Run workflow** → `backup_prefix = manual`.
- The result lands under `s3://arguslog-backups/manual/<date>/…` so it's distinguishable
  from the daily cron output and stays around as long as the bucket lifecycle keeps it.

Alternatively, run `scripts/backup-postgres.sh` from your laptop with the same env vars the
workflow uses (you'll need DB-host network access).

## Restore smoke

A real DR drill against a prod backup is the only thing that proves the restore path works
top-to-bottom — but it's heavy. To catch script-level regressions cheaply, two harnesses
exercise both scripts end-to-end against a MinIO bucket and ephemeral Postgres:

- **Locally** — `bash scripts/restore-smoke.sh` (needs Docker). Spins up source + target +
  MinIO, seeds 1000 rows, runs the real backup + restore scripts, asserts row-count and
  payload parity, then tears everything down. ~60 seconds.
- **CI** — `.github/workflows/restore-smoke.yml` runs the same flow every Monday at 05:00 UTC
  and on any PR that touches the backup/restore scripts or this doc.

Neither smoke touches production data or R2 — they're cheap to run, but if either script
ever regresses (renamed flag, broken env-var guard, wrong S3 key shape), the smoke fails the
same way a real DR would. That's the point.
