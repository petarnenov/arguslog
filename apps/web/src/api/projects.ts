import { apiFetch } from './client';
import type { Dsn } from './keys';

export interface ProjectStats {
  unresolvedIssueCount: number;
  events24h: number;
  events7d: number;
  /** ISO timestamp, or null when the project has never received an event. */
  lastEventAt: string | null;
  /** Always 14 entries, oldest → newest. ISO date in `day`. */
  eventsByDay: { day: string; count: number }[];
}

export type GitProvider = 'github' | 'gitlab';

export interface Project {
  id: number;
  orgId: number;
  slug: string;
  name: string;
  platform: string;
  /**
   * Linked Git host, or null when the project has no repo configured. `gitProvider` and
   * `gitRepo` are always either both populated or both null (server-side CHECK constraint).
   */
  gitProvider: GitProvider | null;
  /** Canonical `owner/repo` (GitHub) or `group/project` / `group/sub/project` (GitLab). */
  gitRepo: string | null;
  createdAt: string;
  /** Populated by the list endpoint; absent on single-project lookups. */
  stats?: ProjectStats;
}

/**
 * Server response from `POST /api/v1/orgs/{orgId}/projects` — bundles the project with its
 * auto-minted first DSN so the UI can show the "copy your key" modal in one round-trip.
 * The full `dsn.dsn` string is visible exactly once here (GH #26 / PAT pattern); subsequent
 * listings return DSN summaries without it.
 */
export interface ProjectCreate {
  project: Project;
  dsn: Dsn;
}

export interface CreateProjectInput {
  name: string;
  platform: string;
  /**
   * Optional Git link. Both fields must be supplied together (or both omitted). When the
   * `gitRepo` field is a URL, the server auto-detects the provider from the host and
   * validates it matches `gitProvider` when both are sent.
   */
  gitProvider?: GitProvider | null;
  gitRepo?: string | null;
}

export interface UpdateProjectInput {
  /** null = leave unchanged. */
  name?: string | null;
  /** Pair "" + "" clears the Git link. Pair (provider, repo) sets it. */
  gitProvider?: string | null;
  gitRepo?: string | null;
}

export interface GitBranch {
  name: string;
  /** Head commit SHA (provider-agnostic — GitHub `commit.sha` / GitLab `commit.id`). */
  sha: string;
}

export function listProjects(orgId: number): Promise<Project[]> {
  return apiFetch<Project[]>(`/api/v1/orgs/${orgId}/projects`);
}

export function createProject(
  orgId: number,
  body: CreateProjectInput,
): Promise<ProjectCreate> {
  return apiFetch<ProjectCreate>(`/api/v1/orgs/${orgId}/projects`, {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

/**
 * Partial update — pass only the fields the caller wants to touch. To clear the Git link, send
 * `gitProvider: ""` together with `gitRepo: ""`. Owner/admin only.
 */
export function updateProject(
  orgId: number,
  projectId: number,
  body: UpdateProjectInput,
): Promise<Project> {
  return apiFetch<Project>(`/api/v1/orgs/${orgId}/projects/${projectId}`, {
    method: 'PATCH',
    body: JSON.stringify(body),
  });
}

/**
 * Backwards-compatible thin wrapper. Prefer {@link updateProject} for new callsites that
 * may also need to touch the Git link.
 */
export function renameProject(orgId: number, projectId: number, name: string): Promise<Project> {
  return updateProject(orgId, projectId, { name });
}

/** Soft-archive: server flips archived_at, project disappears from the live list. */
export function archiveProject(orgId: number, projectId: number): Promise<void> {
  return apiFetch<void>(`/api/v1/orgs/${orgId}/projects/${projectId}`, {
    method: 'DELETE',
  });
}

/**
 * Fetches branches from the project's configured Git host (public API, unauthenticated). Used
 * by the "Create release" form to populate a branch dropdown and auto-fill Git SHA when the
 * user picks a branch.
 *
 * <p>Surfaces a flat list — provider differences (URL encoding, JSON shape) are normalized
 * server-side. Errors map to ApiError with the problem types documented in the controller
 * (`git-repo-missing` = 422, `git-repo-not-found` = 404, `git-rate-limited` = 429,
 * `git-upstream` = 502).
 */
export function listGitBranches(orgId: number, projectId: number): Promise<GitBranch[]> {
  return apiFetch<GitBranch[]>(
    `/api/v1/orgs/${orgId}/projects/${projectId}/git/branches`,
  );
}
