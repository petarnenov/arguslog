import { apiFetch } from './client';

export interface Dsn {
  id: number;
  projectId: number;
  dsnPublic: string;
  dsn: string;
  active: boolean;
  createdAt: string;
}

export function listDsns(projectId: number): Promise<Dsn[]> {
  return apiFetch<Dsn[]>(`/api/v1/projects/${projectId}/keys`);
}

export function createDsn(projectId: number): Promise<Dsn> {
  return apiFetch<Dsn>(`/api/v1/projects/${projectId}/keys`, { method: 'POST' });
}
