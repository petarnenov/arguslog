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

export function listDsns(projectId: number): Promise<DsnSummary[]> {
  return apiFetch<DsnSummary[]>(`/api/v1/projects/${projectId}/keys`);
}

export function createDsn(projectId: number): Promise<Dsn> {
  return apiFetch<Dsn>(`/api/v1/projects/${projectId}/keys`, { method: 'POST' });
}

export function revokeDsn(projectId: number, keyId: number): Promise<void> {
  return apiFetch<void>(`/api/v1/projects/${projectId}/keys/${keyId}`, { method: 'DELETE' });
}
