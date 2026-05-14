-- =====================================================================
-- Widen the `releases` row with deploy/git metadata.
--
-- Existing model carried only (version, created_at) — enough to attach
-- source maps but useless for regression detection across deploys
-- ("which release was live when this issue first appeared?") and for
-- the auto-changelog feature.
--
-- All new columns are nullable so backfill is a no-op — existing rows
-- stay valid; new releases populate them via the CLI or admin UI.
-- =====================================================================
ALTER TABLE releases
  ADD COLUMN released_at  TIMESTAMPTZ NULL,
  ADD COLUMN git_sha      VARCHAR(64) NULL,
  ADD COLUMN git_ref      VARCHAR(255) NULL,
  ADD COLUMN deploy_stage VARCHAR(64) NULL,
  ADD COLUMN changelog    TEXT NULL;

-- Indexes are partial — most rows will have NULL for git_sha / deploy_stage
-- right after migration, so the partial form keeps the index small.

-- Lookup by git SHA — "which release did this commit ship in?". Plays a
-- role in the future regression-attribution path.
CREATE INDEX idx_releases_git_sha
  ON releases(project_id, git_sha)
  WHERE git_sha IS NOT NULL;

-- Listing by deploy stage in descending created_at — "what's the last 10
-- production releases?". Covers the dashboard filter case.
CREATE INDEX idx_releases_deploy_stage
  ON releases(project_id, deploy_stage, created_at DESC)
  WHERE deploy_stage IS NOT NULL;
