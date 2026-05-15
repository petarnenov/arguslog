import { z } from 'zod';

/**
 * Returns a dev-mode default URL that follows the host the page was loaded from. Used so
 * `make dev` works zero-config: laptop hits `http://localhost:5173` → bundle resolves the
 * api at `http://localhost:8081`; phone on the LAN hits `http://192.168.0.186:5173` → bundle
 * resolves the api at `http://192.168.0.186:8081`.
 *
 * <p><strong>Refuses to silently lie in production.</strong> If the page is served from a
 * non-dev hostname (anything outside localhost / RFC1918 / loopback) AND no runtime or
 * build-time override is set, this throws at module load. The previous "always return
 * &lt;host&gt;:&lt;port&gt;" behavior shipped users a plausible-looking but unreachable URL
 * (e.g. `https://app.arguslog.org:8080/api/9/events` — DNS resolves, port 8080 is closed
 * over Cloudflare, browser reports a mystery `Failed to fetch`) — exactly the failure mode
 * this guard prevents from sneaking past the operator.
 */
function devDefault(envVarName: string, port: number): string {
  if (typeof window === 'undefined' || !window.location?.hostname) {
    return `http://localhost:${port}`;
  }
  const host = window.location.hostname;
  if (isDevHost(host)) {
    return `${window.location.protocol}//${host}:${port}`;
  }
  throw new Error(
    `${envVarName} is required in production but neither the runtime config ` +
      `(ARGUSLOG_WEB_*) nor the build-time env (VITE_*) provided a value, and the page is ` +
      `served from "${host}" — which doesn't look like a local-dev host. Self-hosters: set ` +
      `the env var on your web container and restart it (the entrypoint re-renders ` +
      `/srv/runtime-config.js at boot).`,
  );
}

function isDevHost(host: string): boolean {
  if (host === 'localhost' || host === '0.0.0.0' || host === '::1') return true;
  if (host.startsWith('127.')) return true;
  // RFC1918 private ranges: 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16.
  const parts = host.split('.').map((s) => Number(s));
  if (parts.length !== 4 || parts.some((n) => Number.isNaN(n) || n < 0 || n > 255)) {
    return false;
  }
  const [a, b] = parts as [number, number, number, number];
  if (a === 10) return true;
  if (a === 192 && b === 168) return true;
  if (a === 172 && b >= 16 && b <= 31) return true;
  return false;
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
  // .default(() => fn()) is the lazy form — fn() runs only when no value is provided.
  // Critical so a misconfig throws only when there's truly no override, not every parse.
  VITE_API_BASE_URL: z
    .string()
    .url()
    .default(() => devDefault('ARGUSLOG_WEB_API_BASE_URL', 8081)),
  VITE_INGEST_BASE_URL: z
    .string()
    .url()
    .default(() => devDefault('ARGUSLOG_WEB_INGEST_BASE_URL', 8080)),
  VITE_KEYCLOAK_URL: z
    .string()
    .url()
    .default(() => devDefault('ARGUSLOG_WEB_KEYCLOAK_URL', 8180)),
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
