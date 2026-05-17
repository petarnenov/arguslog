/**
 * Playwright config for the Arguslog E2E suite.
 *
 * Tests target the live staging environment by default (`https://app.arguslog.org` for
 * the dashboard, `https://arguslog.org` for the landing page). Both URLs are
 * configurable via env vars so the same suite can run against `make demo` locally
 * (`http://localhost:5173` / `http://localhost:5174`) or against a self-hosted
 * stack.
 *
 * Cross-origin caveat: the dashboard tests round-trip through Keycloak when the
 * programmatic OIDC fixture isn't used (e.g. the dedicated auth.spec.ts), which is
 * why the per-test timeout is generous — Railway cold starts can add a few seconds
 * to the first request hitting an idle staging service.
 */
import { defineConfig, devices } from '@playwright/test';

const baseURL = process.env.ARGUSLOG_E2E_BASE_URL ?? 'http://localhost:5173';
const landingURL = process.env.ARGUSLOG_E2E_LANDING_URL ?? 'http://localhost:5174';
const isCI = Boolean(process.env.CI);

export default defineConfig({
  testDir: './tests',
  // 60s accommodates Railway cold-start on idle staging services plus the
  // programmatic-OIDC token roundtrip + initial dashboard render.
  timeout: 60_000,
  expect: { timeout: 10_000 },
  fullyParallel: true,
  forbidOnly: isCI,
  retries: isCI ? 2 : 0,
  workers: isCI ? 2 : undefined,
  reporter: isCI ? [['github'], ['html', { open: 'never' }]] : [['list']],
  use: {
    baseURL,
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
  },
  // Two named projects so a test can opt into either origin via `test.use({ baseURL: …})`
  // — used by landing specs that need to hit the public site, not the dashboard.
  projects: [
    {
      name: 'dashboard',
      testDir: './tests/dashboard',
      use: { ...devices['Desktop Chrome'], baseURL },
    },
    {
      name: 'landing',
      testDir: './tests/landing',
      use: { ...devices['Desktop Chrome'], baseURL: landingURL },
    },
    {
      name: 'auth',
      testDir: './tests/auth',
      use: { ...devices['Desktop Chrome'], baseURL },
    },
  ],
});

/**
 * Runtime config the fixtures read at test time. The dashboard authority + realm
 * are still needed to construct the correct `oidc.user:…` localStorage key (matches
 * `apps/web/src/auth/userManager.ts`); the access token itself is the PAT, not a
 * real Keycloak-issued JWT.
 */
export const e2eConfig = {
  baseURL,
  landingURL,
  apiURL: process.env.ARGUSLOG_E2E_API_URL ?? 'https://arguslog.org',
  // Ingest is a separate Railway service from `api` — DSN-authed event POSTs land
  // on `/api/{projectId}/events` exposed by the ingest service, NOT the dashboard
  // API. Mixing them up returns 401 because the api service has no such route.
  ingestURL: process.env.ARGUSLOG_E2E_INGEST_URL ?? 'https://arguslog-ingest-staging.up.railway.app',
  keycloakURL:
    process.env.ARGUSLOG_E2E_KEYCLOAK_URL ?? 'https://arguslog-keycloak-staging.up.railway.app',
  keycloakRealm: process.env.ARGUSLOG_E2E_KEYCLOAK_REALM ?? 'arguslog',
  runnerPAT: process.env.ARGUSLOG_E2E_RUNNER_PAT ?? '',
  runId: process.env.GITHUB_RUN_ID ?? `local-${Date.now().toString(36)}`,
};
