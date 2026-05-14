/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_BASE_URL?: string;
  readonly VITE_INGEST_BASE_URL?: string;
  readonly VITE_KEYCLOAK_URL?: string;
  readonly VITE_KEYCLOAK_REALM?: string;
  readonly VITE_KEYCLOAK_CLIENT_ID?: string;
  readonly VITE_DOGFOOD_DSN?: string;
  readonly VITE_RELEASE?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}

/**
 * Runtime config injected by the container entrypoint via /runtime-config.js BEFORE the main
 * bundle parses. env.ts reads this first, then falls back to the build-time VITE_* values.
 * Every field is optional + may be the empty string when the corresponding ARGUSLOG_WEB_*
 * env var isn't set on the container — env.ts treats empty as "unset" so the dev defaults
 * still win.
 */
interface ArguslogRuntimeConfig {
  apiBaseUrl?: string;
  ingestBaseUrl?: string;
  keycloakUrl?: string;
  keycloakRealm?: string;
  keycloakClientId?: string;
  dogfoodDsn?: string;
  release?: string;
}

interface Window {
  __ARGUSLOG_CONFIG__?: ArguslogRuntimeConfig;
}
