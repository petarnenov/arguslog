import { apiFetch } from './client';

export interface Project {
  id: number;
  orgId: number;
  slug: string;
  name: string;
  platform: string;
  createdAt: string;
}

export function listProjects(orgId: number): Promise<Project[]> {
  return apiFetch<Project[]>(`/api/v1/orgs/${orgId}/projects`);
}

export function createProject(
  orgId: number,
  body: { name: string; platform: string },
): Promise<Project> {
  return apiFetch<Project>(`/api/v1/orgs/${orgId}/projects`, {
    method: 'POST',
    body: JSON.stringify(body),
  });
}
