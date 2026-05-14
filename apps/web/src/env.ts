import { z } from 'zod';

/**
 * Returns a development default URL that follows the host the page was loaded
 * from. So if `make dev` is launched on a laptop and a phone hits
 * `http://192.168.0.186:5173`, the bundle will fetch the api at
 * `http://192.168.0.186:8081` instead of `localhost:8081` (which would resolve
 * on the phone, not the dev box). Keeps zero-config local dev working AND
 * cross-device dev working without a per-machine `.env.local`.
 */
function devDefault(port: number): string {
  if (typeof window !== 'undefined' && window.location?.hostname) {
    return `${window.location.protocol}//${window.location.hostname}:${port}`;
  }
  return `http://localhost:${port}`;
}

/**
 * Three-stage resolution per setting:
 *   1. {@link window.__ARGUSLOG_CONFIG__} — injected by the container entrypoint from
 *      ARGUSLOG_WEB_* env vars; self-hosters edit those without rebuilding the image.
 *   2. {@code import.meta.env.VITE_*} — baked at build time; what Railway's prod / staging
 *      images use today via build-args.
 *   3. {@link devDefault} — localhost-follows-hostname so `make dev` works zero-config.
 *
 * Empty strings from the runtime config are treated as "unset" so a self-hoster who left
 * one variable blank doesn't get an empty-string URL passed to fetch / OIDC.
 */
function pickRuntime(key: keyof ArguslogRuntimeConfig): string | undefined {
  if (typeof window === 'undefined') return undefined;
  const raw = window.__ARGUSLOG_CONFIG__?.[key];
  if (typeof raw !== 'string') return undefined;
  const trimmed = raw.trim();
  return trimmed.length === 0 ? undefined : trimmed;
}

function resolve(
  runtimeKey: keyof ArguslogRuntimeConfig,
  buildTime: string | undefined,
): string | undefined {
  return pickRuntime(runtimeKey) ?? (buildTime?.trim() ? buildTime : undefined);
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
  VITE_API_BASE_URL: resolve('apiBaseUrl', import.meta.env.VITE_API_BASE_URL),
  VITE_INGEST_BASE_URL: resolve('ingestBaseUrl', import.meta.env.VITE_INGEST_BASE_URL),
  VITE_KEYCLOAK_URL: resolve('keycloakUrl', import.meta.env.VITE_KEYCLOAK_URL),
  VITE_KEYCLOAK_REALM: resolve('keycloakRealm', import.meta.env.VITE_KEYCLOAK_REALM),
  VITE_KEYCLOAK_CLIENT_ID: resolve('keycloakClientId', import.meta.env.VITE_KEYCLOAK_CLIENT_ID),
  VITE_DOGFOOD_DSN: resolve('dogfoodDsn', import.meta.env.VITE_DOGFOOD_DSN),
  VITE_RELEASE: resolve('release', import.meta.env.VITE_RELEASE),
});
