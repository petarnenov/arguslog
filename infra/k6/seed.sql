-- Bootstrap an org+project+DSN for k6 baseline runs.
-- Idempotent (ON CONFLICT DO NOTHING) so re-running is safe.
--
-- Apply against staging via:
--   railway ssh --service timescaledb -- "psql -U arguslog -d arguslog -c \"$(cat infra/k6/seed.sql)\""
-- (or paste statement-by-statement; the railway-ssh quoting layer eats multi-line input.)

INSERT INTO organizations (id, slug, name, plan)
  VALUES (1, 'k6-bench', 'k6 Bench Org', 'pro')
  ON CONFLICT (id) DO NOTHING;

INSERT INTO projects (id, org_id, slug, name, platform)
  VALUES (101, 1, 'k6-bench', 'k6 Bench Project', 'javascript')
  ON CONFLICT (id) DO NOTHING;

INSERT INTO project_keys (project_id, dsn_public, dsn_secret_hash, active)
  VALUES (101, 'k6_bench_pk_01HXYZK6BENCHPUBLIC0001', NULL, TRUE)
  ON CONFLICT (dsn_public) DO NOTHING;
