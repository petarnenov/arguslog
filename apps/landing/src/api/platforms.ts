import { env } from '../env';

export interface Platform {
  slug: string;
  name: string;
  sdkPackage: string | null;
  sdkVersion: string | null;
}

/**
 * Mirror of the dashboard's platforms client, but landing-side: read-only, anonymous (the
 * /api/v1/platforms endpoint is permitAll). Showing the live catalog instead of a hardcoded list
 * means a new SDK on the api auto-appears here without a marketing-page deploy.
 */
export async function listPlatforms(): Promise<Platform[]> {
  const res = await fetch(`${env.VITE_API_BASE_URL}/api/v1/platforms`, {
    headers: { Accept: 'application/json' },
  });
  if (!res.ok) {
    throw new Error(`platforms request failed: HTTP ${res.status}`);
  }
  return (await res.json()) as Platform[];
}
