import { apiFetch } from './client';

export interface Me {
  userId: string;
  email: string | null;
  displayName: string | null;
  isPlatformAdmin: boolean;
  /** User's effective tier (regular / silver / gold / platinum). */
  tier: string;
  /** When an admin-granted tier expires, null for permanent / default-regular users. */
  tierExpiresAt: string | null;
  /** Optional admin-supplied reason for the grant, null if no active grant. */
  tierReason: string | null;
}

export function getMe(): Promise<Me> {
  return apiFetch<Me>('/api/v1/me');
}
