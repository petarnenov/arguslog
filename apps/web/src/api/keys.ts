import { apiFetch } from './client';

/**
 * Listing-time view of a DSN. Mirrors `DsnSummaryResponse` on the server: the full
 * DSN string is intentionally absent — it's returned only by `createDsn` at minting
 * time. Once a key has been created, the dashboard can show only metadata + a revoke
 * action, mirroring how GitHub PATs work.
 */
export interface DsnSummary {
  id: number;
  projectId: number;
  dsnPublic: string;
  active: boolean;
  createdAt: string;
}

/** Full DSN payload — returned exactly once, by `createDsn`. */
export interface Dsn extends DsnSummary {
  dsn: string;
}

/**
 * Active DSNs by default. Pass {@code includeRevoked: true} when the dashboard needs the audit /
 * rotation-history view — the backend orders active rows first, then revoked, newest within
 * each group.
 */
export function listDsns(
  projectId: number,
  options: { includeRevoked?: boolean } = {},
): Promise<DsnSummary[]> {
  const qs = options.includeRevoked ? '?includeRevoked=true' : '';
  return apiFetch<DsnSummary[]>(`/api/v1/projects/${projectId}/keys${qs}`);
}

export function createDsn(projectId: number): Promise<Dsn> {
  return apiFetch<Dsn>(`/api/v1/projects/${projectId}/keys`, { method: 'POST' });
}

export function revokeDsn(projectId: number, keyId: number): Promise<void> {
  return apiFetch<void>(`/api/v1/projects/${projectId}/keys/${keyId}`, { method: 'DELETE' });
}
