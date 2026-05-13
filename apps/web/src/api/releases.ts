import { apiFetch } from './client';

export interface Release {
  id: number;
  projectId: number;
  version: string;
  createdAt: string;
}

export function listReleases(projectId: number): Promise<Release[]> {
  return apiFetch<Release[]>(`/api/v1/projects/${projectId}/releases`);
}

export function getRelease(projectId: number, id: number): Promise<Release> {
  return apiFetch<Release>(`/api/v1/projects/${projectId}/releases/${id}`);
}

export function createRelease(projectId: number, version: string): Promise<Release> {
  return apiFetch<Release>(`/api/v1/projects/${projectId}/releases`, {
    method: 'POST',
    body: JSON.stringify({ version }),
  });
}

export function updateRelease(projectId: number, id: number, version: string): Promise<Release> {
  return apiFetch<Release>(`/api/v1/projects/${projectId}/releases/${id}`, {
    method: 'PUT',
    body: JSON.stringify({ version }),
  });
}

export function deleteRelease(projectId: number, id: number): Promise<void> {
  return apiFetch<void>(`/api/v1/projects/${projectId}/releases/${id}`, { method: 'DELETE' });
}
