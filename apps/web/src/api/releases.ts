import { apiFetch } from './client';

export interface Release {
  id: number;
  projectId: number;
  version: string;
  createdAt: string;
  /** Operator-declared deploy moment. Independent of {@link createdAt} (row-insert time). */
  releasedAt: string | null;
  gitSha: string | null;
  gitRef: string | null;
  deployStage: string | null;
  changelog: string | null;
}

/**
 * Operator-supplied payload for create / update. `version` is required; the metadata fields are
 * optional and treated as full-PUT semantics on update (null clears the column).
 */
export interface ReleaseInput {
  version: string;
  releasedAt?: string | null;
  gitSha?: string | null;
  gitRef?: string | null;
  deployStage?: string | null;
  changelog?: string | null;
}

export function listReleases(projectId: number): Promise<Release[]> {
  return apiFetch<Release[]>(`/api/v1/projects/${projectId}/releases`);
}

export function getRelease(projectId: number, id: number): Promise<Release> {
  return apiFetch<Release>(`/api/v1/projects/${projectId}/releases/${id}`);
}

export function createRelease(
  projectId: number,
  input: ReleaseInput | string,
): Promise<Release> {
  // Tolerate legacy callsites that still pass a bare version string — wrap it transparently so
  // we don't need to touch every test/MCP/CLI caller in this same PR.
  const body: ReleaseInput = typeof input === 'string' ? { version: input } : input;
  return apiFetch<Release>(`/api/v1/projects/${projectId}/releases`, {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

export function updateRelease(
  projectId: number,
  id: number,
  input: ReleaseInput | string,
): Promise<Release> {
  const body: ReleaseInput = typeof input === 'string' ? { version: input } : input;
  return apiFetch<Release>(`/api/v1/projects/${projectId}/releases/${id}`, {
    method: 'PUT',
    body: JSON.stringify(body),
  });
}

export function deleteRelease(projectId: number, id: number): Promise<void> {
  return apiFetch<void>(`/api/v1/projects/${projectId}/releases/${id}`, { method: 'DELETE' });
}

/**
 * Issues whose `first_seen_release_id` equals the given release — the regression-watchlist that
 * the release detail page renders below the source-maps card. Backend caps the list at 200 rows.
 * Shape mirrors the issues list endpoint so existing Issue rendering / row links work unchanged.
 */
export function listIssuesIntroducedInRelease(
  projectId: number,
  releaseId: number,
): Promise<import('./issues').Issue[]> {
  return apiFetch<import('./issues').Issue[]>(
    `/api/v1/projects/${projectId}/releases/${releaseId}/issues`,
  );
}
