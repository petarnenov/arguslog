import { apiFetch } from './client';
import type { Dsn } from './keys';

export interface Project {
  id: number;
  orgId: number;
  slug: string;
  name: string;
  platform: string;
  createdAt: string;
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

/** Soft-archive: server flips archived_at, project disappears from the live list. */
export function archiveProject(orgId: number, projectId: number): Promise<void> {
  return apiFetch<void>(`/api/v1/orgs/${orgId}/projects/${projectId}`, {
    method: 'DELETE',
  });
}
