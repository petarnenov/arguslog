import { apiFetch } from './client';

export interface Me {
  userId: string;
  email: string | null;
  displayName: string | null;
  isPlatformAdmin: boolean;
  /** User's effective billing tier (per-user billing, V26+). */
  plan: string;
  planRenewsAt: string | null;
  paymentGraceUntil: string | null;
  bonusUntil: string | null;
  bonusReason: string | null;
}

export function getMe(): Promise<Me> {
  return apiFetch<Me>('/api/v1/me');
}
