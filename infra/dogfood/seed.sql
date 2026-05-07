-- Bootstrap the dogfood org + per-service projects + DSNs.
-- Idempotent (ON CONFLICT DO NOTHING) so re-running on staging is safe.
--
-- Apply via:
--   railway ssh --service timescaledb -- 'psql -U arguslog -d arguslog -c "<each statement>"'
-- (railway ssh's quoting layer doesn't survive a multi-line heredoc; paste statements one
-- by one or use the inline runner script below the file's tail.)

INSERT INTO organizations (id, slug, name, plan)
  VALUES (2, 'arguslog-internal', 'Arguslog Internal', 'enterprise')
  ON CONFLICT (id) DO NOTHING;

INSERT INTO projects (id, org_id, slug, name, platform)
  VALUES
    (201, 2, 'arguslog-api',    'Arguslog API',    'java'),
    (202, 2, 'arguslog-ingest', 'Arguslog Ingest', 'java'),
    (203, 2, 'arguslog-worker', 'Arguslog Worker', 'java'),
    (204, 2, 'arguslog-web',    'Arguslog Web',    'javascript')
  ON CONFLICT (id) DO NOTHING;

INSERT INTO project_keys (project_id, dsn_public, dsn_secret_hash, active)
  VALUES
    (201, 'arglog_dogfood_pk_API_01HXYZARGUSLOGAPI00000001',    NULL, TRUE),
    (202, 'arglog_dogfood_pk_INGEST_01HXYZARGUSLOGINGEST0001',  NULL, TRUE),
    (203, 'arglog_dogfood_pk_WORKER_01HXYZARGUSLOGWORKER0001',  NULL, TRUE),
    (204, 'arglog_dogfood_pk_WEB_01HXYZARGUSLOGWEB00000001',    NULL, TRUE)
  ON CONFLICT (dsn_public) DO NOTHING;

SELECT pk.dsn_public, p.slug
  FROM project_keys pk
  JOIN projects p ON p.id = pk.project_id
 WHERE p.org_id = 2;
