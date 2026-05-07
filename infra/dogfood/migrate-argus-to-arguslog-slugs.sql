-- One-off migration: rename dogfood project slugs argus-* → arguslog-* in the
-- arguslog-internal org (org_id=2). Triggered by the codebase rename in commit
-- 2137544 — projects were originally seeded as `argus-api/ingest/worker/web`, and
-- humans browsing /arguslog-internal/<slug>/issues need slugs that match the new
-- service names.
--
-- Idempotent: WHERE-clauses scope updates by old slug, so re-running on a DB
-- that's already been migrated is a no-op (zero rows match).
--
-- DSN public keys (project_keys.dsn_public) are intentionally NOT touched — they
-- are opaque random IDs and the Railway env vars (ARGUSLOG_DSN) still point at
-- the original `01HXYZARGUS*` strings. seed.sql was reverted to match.
--
-- Apply via:
--   railway ssh --service timescaledb --environment <staging|production> \
--     -- 'psql -U arguslog -d arguslog -c "<each statement>"'
-- (One statement per invocation; ssh quoting layer doesn't survive heredocs.)

UPDATE projects SET slug = 'arguslog-api',    name = 'Arguslog API'    WHERE org_id = 2 AND slug = 'argus-api';
UPDATE projects SET slug = 'arguslog-ingest', name = 'Arguslog Ingest' WHERE org_id = 2 AND slug = 'argus-ingest';
UPDATE projects SET slug = 'arguslog-worker', name = 'Arguslog Worker' WHERE org_id = 2 AND slug = 'argus-worker';
UPDATE projects SET slug = 'arguslog-web',    name = 'Arguslog Web'    WHERE org_id = 2 AND slug = 'argus-web';

-- Verify the four expected rows:
SELECT id, slug, name FROM projects WHERE org_id = 2 ORDER BY id;
