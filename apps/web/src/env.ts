import { z } from 'zod';

/**
 * Returns a development default URL that follows the host the page was loaded
 * from. So if `make dev` is launched on a laptop and a phone hits
 * `http://192.168.0.186:5173`, the bundle will fetch the api at
 * `http://192.168.0.186:8081` instead of `localhost:8081` (which would resolve
 * on the phone, not the dev box). Keeps zero-config local dev working AND
 * cross-device dev working without a per-machine `.env.local`.
 *
 * In production the build-time `VITE_API_BASE_URL` env (set by Railway) wins,
 * so this fallback never runs there.
 */
function devDefault(port: number): string {
  if (typeof window !== 'undefined' && window.location?.hostname) {
    return `${window.location.protocol}//${window.location.hostname}:${port}`;
  }
  return `http://localhost:${port}`;
}

const envSchema = z.object({
  VITE_API_BASE_URL: z.string().url().default(devDefault(8081)),
  VITE_INGEST_BASE_URL: z.string().url().default(devDefault(8080)),
  VITE_KEYCLOAK_URL: z.string().url().default(devDefault(8180)),
  VITE_KEYCLOAK_REALM: z.string().default('arguslog'),
  VITE_KEYCLOAK_CLIENT_ID: z.string().default('arguslog-web'),
  VITE_DOGFOOD_DSN: z.string().optional(),
  VITE_RELEASE: z.string().default('dev'),
});

export const env = envSchema.parse({
  VITE_API_BASE_URL: import.meta.env.VITE_API_BASE_URL,
  VITE_INGEST_BASE_URL: import.meta.env.VITE_INGEST_BASE_URL,
  VITE_KEYCLOAK_URL: import.meta.env.VITE_KEYCLOAK_URL,
  VITE_KEYCLOAK_REALM: import.meta.env.VITE_KEYCLOAK_REALM,
  VITE_KEYCLOAK_CLIENT_ID: import.meta.env.VITE_KEYCLOAK_CLIENT_ID,
  VITE_DOGFOOD_DSN: import.meta.env.VITE_DOGFOOD_DSN,
  VITE_RELEASE: import.meta.env.VITE_RELEASE,
});
