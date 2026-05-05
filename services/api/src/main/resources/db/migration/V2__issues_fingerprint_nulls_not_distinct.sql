-- V1 declared `UNIQUE (project_id, environment_id, fingerprint)` on issues. Postgres treats NULL
-- as distinct in unique constraints by default, so two rows with environment_id IS NULL would both
-- be accepted — and the worker's ON CONFLICT issue upsert would silently create duplicates instead
-- of bumping the existing issue. Fix with NULLS NOT DISTINCT (PG 15+).

ALTER TABLE issues
  DROP CONSTRAINT IF EXISTS issues_project_id_environment_id_fingerprint_key;

ALTER TABLE issues
  ADD CONSTRAINT issues_project_env_fingerprint_uniq
  UNIQUE NULLS NOT DISTINCT (project_id, environment_id, fingerprint);
