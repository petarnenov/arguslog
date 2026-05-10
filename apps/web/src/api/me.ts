import { apiFetch } from './client';

export interface Me {
  userId: string;
  email: string | null;
  displayName: string | null;
  isPlatformAdmin: boolean;
}

export function getMe(): Promise<Me> {
  return apiFetch<Me>('/api/v1/me');
}
