import { apiFetch } from './client';

export type MemberRole = 'owner' | 'admin' | 'member';

export interface OrgMember {
  userId: string;
  email: string;
  displayName: string | null;
  role: MemberRole;
  addedAt: string;
}

export function listOrgMembers(orgId: number): Promise<OrgMember[]> {
  return apiFetch<OrgMember[]>(`/api/v1/orgs/${orgId}/members`);
}

export function inviteOrgMember(
  orgId: number,
  body: { email: string; role: MemberRole },
): Promise<OrgMember> {
  return apiFetch<OrgMember>(`/api/v1/orgs/${orgId}/members`, {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

export function changeOrgMemberRole(
  orgId: number,
  userId: string,
  role: MemberRole,
): Promise<OrgMember> {
  return apiFetch<OrgMember>(`/api/v1/orgs/${orgId}/members/${userId}`, {
    method: 'PATCH',
    body: JSON.stringify({ role }),
  });
}

export function removeOrgMember(orgId: number, userId: string): Promise<void> {
  return apiFetch<void>(`/api/v1/orgs/${orgId}/members/${userId}`, { method: 'DELETE' });
}
