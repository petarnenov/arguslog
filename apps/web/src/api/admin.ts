import { apiFetch } from './client';

export interface AdminStats {
  totalUsers: number;
  totalOrgs: number;
  totalProjects: number;
  totalIssues: number;
  orgsByPlan: Record<string, number>;
  activeBonusGrants: number;
  events7d: number;
  events30d: number;
}

export interface AdminUser {
  userId: string;
  email: string | null;
  displayName: string | null;
  createdAt: string;
  ownedOrgs: number;
  memberOrgs: number;
  highestPlan: string | null;
}

export interface AdminOrg {
  orgId: number;
  slug: string;
  name: string;
  tier: string;
  createdAt: string;
  ownerId: string | null;
  ownerEmail: string | null;
  projects: number;
  members: number;
  events30d: number;
  tierExpiresAt: string | null;
  tierReason: string | null;
  tierGrantedByEmail: string | null;
}

export interface AdminAuditEntry {
  id: number;
  ts: string;
  adminUser: string | null;
  adminEmail: string;
  action: string;
  targetType: string;
  targetId: string;
  payload: unknown;
}

export interface AdminPage<T> {
  items: T[];
  total: number;
  offset: number;
  limit: number;
}

export type GrantTier = 'silver' | 'gold' | 'platinum';
/** Duration in months; 0 means a permanent grant with no auto-expiry. */
export type GrantMonths = 0 | 1 | 3 | 6 | 12;

export function getAdminStats(): Promise<AdminStats> {
  return apiFetch<AdminStats>('/api/v1/admin/stats');
}

export function listAdminUsers(params: {
  q?: string;
  offset?: number;
  limit?: number;
}): Promise<AdminPage<AdminUser>> {
  return apiFetch<AdminPage<AdminUser>>(`/api/v1/admin/users?${toQuery(params)}`);
}

export function listAdminOrgs(params: {
  q?: string;
  offset?: number;
  limit?: number;
}): Promise<AdminPage<AdminOrg>> {
  return apiFetch<AdminPage<AdminOrg>>(`/api/v1/admin/orgs?${toQuery(params)}`);
}

export function listAdminAudit(params: {
  offset?: number;
  limit?: number;
}): Promise<AdminPage<AdminAuditEntry>> {
  return apiFetch<AdminPage<AdminAuditEntry>>(`/api/v1/admin/audit?${toQuery(params)}`);
}

/** Per-user tier grant — covers every org the user owns automatically. */
export function grantUserTier(
  userId: string,
  body: { tier: GrantTier; months: GrantMonths; reason: string },
): Promise<void> {
  return apiFetch<void>(`/api/v1/admin/users/${userId}/grant`, {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

export function revokeUserTier(userId: string): Promise<void> {
  return apiFetch<void>(`/api/v1/admin/users/${userId}/grant`, { method: 'DELETE' });
}

function toQuery(params: Record<string, unknown>): string {
  const out = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v === undefined || v === null || v === '') continue;
    out.set(k, String(v));
  }
  return out.toString();
}
