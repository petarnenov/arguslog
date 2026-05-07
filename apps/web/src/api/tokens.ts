import { apiFetch } from './client';

// Wire strings shared 1:1 with PatScope.java. Order matches the on-screen group.
export const PAT_SCOPES = [
  'orgs:read',
  'orgs:write',
  'projects:read',
  'projects:write',
  'issues:read',
  'events:read',
  'releases:read',
  'releases:write',
  'sourcemaps:write',
  'alerts:read',
  'alerts:write',
] as const;

export type PatScope = (typeof PAT_SCOPES)[number];

export interface PersonalAccessToken {
  id: number;
  name: string;
  prefix: string;
  /** Only present on the POST response — the server cannot recover plaintext after issue. */
  token?: string;
  expiresAt?: string;
  lastUsedAt?: string;
  createdAt: string;
  /**
   * Wire-form scope list. {@code undefined} on the wire means "implicit-all" — a token minted
   * before the scopes column existed (V12). Render those tokens as full-access.
   */
  scopes?: PatScope[];
}

export interface CreatePatRequest {
  name: string;
  expiresAt?: string | null;
  /** Pass {@code undefined} to keep the implicit-all contract; pass an explicit list to restrict. */
  scopes?: PatScope[];
}

export function listMyTokens(): Promise<PersonalAccessToken[]> {
  return apiFetch<PersonalAccessToken[]>('/api/v1/me/tokens');
}

export function createMyToken(req: CreatePatRequest): Promise<PersonalAccessToken> {
  return apiFetch<PersonalAccessToken>('/api/v1/me/tokens', {
    method: 'POST',
    body: JSON.stringify(req),
  });
}

export function revokeMyToken(id: number): Promise<void> {
  return apiFetch<void>(`/api/v1/me/tokens/${id}`, { method: 'DELETE' });
}
