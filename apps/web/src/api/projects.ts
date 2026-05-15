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

export interface Project {
  id: number;
  orgId: number;
  slug: string;
  name: string;
  platform: string;
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

export function listProjects(orgId: number): Promise<Project[]> {
  return apiFetch<Project[]>(`/api/v1/orgs/${orgId}/projects`);
}

export function createProject(
  orgId: number,
  body: { name: string; platform: string },
): Promise<ProjectCreate> {
  return apiFetch<ProjectCreate>(`/api/v1/orgs/${orgId}/projects`, {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

/** Owner/admin only. Renames display name; slug stays so DSN URLs remain valid. */
export function renameProject(
  orgId: number,
  projectId: number,
  name: string,
): Promise<Project> {
  return apiFetch<Project>(`/api/v1/orgs/${orgId}/projects/${projectId}`, {
    method: 'PATCH',
    body: JSON.stringify({ name }),
  });
}

/** Soft-archive: server flips archived_at, project disappears from the live list. */
export function archiveProject(orgId: number, projectId: number): Promise<void> {
  return apiFetch<void>(`/api/v1/orgs/${orgId}/projects/${projectId}`, {
    method: 'DELETE',
  });
}
