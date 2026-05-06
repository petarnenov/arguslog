-- =====================================================================
-- source_map_artifacts: RLS + uniqueness V1 forgot.
--
-- V1 created the table with no isolation policy and no UNIQUE on
-- (release_id, original_path), which means:
--   1. another tenant could read your r2_keys by guessing release_id
--   2. a re-upload from a rebuild would accumulate duplicate rows,
--      leaving the worker with a non-deterministic SELECT.
-- Plug both gaps before the api endpoint goes live (P3 #9).
-- =====================================================================
ALTER TABLE source_map_artifacts ENABLE ROW LEVEL SECURITY;

-- Two-hop isolation: artifact -> release -> project -> org.
CREATE POLICY source_map_artifacts_org_isolation ON source_map_artifacts
  USING (release_id IN (
    SELECT r.id
      FROM releases r
      JOIN projects p ON p.id = r.project_id
     WHERE p.org_id = current_setting('arguslog.org_id', true)::BIGINT
  ));

-- Required for the upsert path that replaces stale uploads.
ALTER TABLE source_map_artifacts
  ADD CONSTRAINT uq_source_map_artifacts_release_path
  UNIQUE (release_id, original_path);
