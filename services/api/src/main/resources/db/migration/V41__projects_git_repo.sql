-- =====================================================================
-- Optional Git repository reference per project, used by the "Create
-- release" form to auto-populate Git ref + Git SHA from a branch dropdown
-- by proxying the provider's public branches API.
--
-- Two columns instead of one host-locked field so we don't repeat the
-- mistake of zeroing in on a single provider: `git_provider` picks which
-- public API to talk to, `git_repo` carries the canonical `owner/repo`
-- (GitHub) or `group/project` / `group/subgroup/project` (GitLab) path.
-- Both nullable: NULL/NULL = "no repo linked". A CHECK keeps them in sync
-- (either both set or both null) so the worker never sees half-state.
--
-- Public hosts only for now (github.com, gitlab.com). Self-hosted
-- (GitLab CE, GitHub Enterprise) is a separate plan that adds a
-- `git_host` column — its absence here keeps validation simple and
-- avoids per-host CORS/rate-limit knobs we don't need yet.
--
-- Length 512 on git_repo accommodates GitLab nested-group paths
-- (each segment up to 255 by GitLab's own rule); 16 on git_provider is
-- ample for short slugs like 'github' / 'gitlab' / future 'bitbucket'.
-- =====================================================================

ALTER TABLE projects
  ADD COLUMN git_provider VARCHAR(16),
  ADD COLUMN git_repo     VARCHAR(512),
  ADD CONSTRAINT chk_projects_git_repo_pair
    CHECK ((git_provider IS NULL) = (git_repo IS NULL)),
  ADD CONSTRAINT chk_projects_git_provider_known
    CHECK (git_provider IS NULL OR git_provider IN ('github', 'gitlab'));
