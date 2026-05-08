import { apiFetch } from './client';

export interface Platform {
  slug: string;
  name: string;
  sdkPackage: string | null;
  sdkVersion: string | null;
}

export function listPlatforms(): Promise<Platform[]> {
  return apiFetch<Platform[]>('/api/v1/platforms');
}
