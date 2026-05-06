import { apiFetch } from './client';

export interface PersonalAccessToken {
  id: number;
  name: string;
  prefix: string;
  /** Only present on the POST response — the server cannot recover plaintext after issue. */
  token?: string;
  expiresAt?: string;
  lastUsedAt?: string;
  createdAt: string;
}

export interface CreatePatRequest {
  name: string;
  expiresAt?: string | null;
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
