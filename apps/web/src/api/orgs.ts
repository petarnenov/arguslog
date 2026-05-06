import { apiFetch } from './client';

export interface Org {
  id: number;
  slug: string;
  name: string;
  plan: string;
  createdAt: string;
}

export function listMyOrgs(): Promise<Org[]> {
  return apiFetch<Org[]>('/api/v1/orgs');
}

export function createOrg(name: string): Promise<Org> {
  return apiFetch<Org>('/api/v1/orgs', {
    method: 'POST',
    body: JSON.stringify({ name }),
  });
}

/** Hard delete; cascades through projects/issues/events/etc. Owner only. */
export function deleteOrg(orgId: number): Promise<void> {
  return apiFetch<void>(`/api/v1/orgs/${orgId}`, { method: 'DELETE' });
}
