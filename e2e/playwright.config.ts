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
// `devices['Desktop Chrome']` ships viewport 1280×720, which on the landing's Mantine
// header is right at the breakpoint where the theme-toggle ActionIcon collapses into a
// burger — `theme-toggle.spec.ts` then skips itself with "toggle not visible in this
// viewport". Pin to 1440×900 (well above every Mantine `lg`/`xl` breakpoint we care
// about) so the toggle, the platforms grid, and the agent-install row all render in
// their full desktop layout regardless of headless vs headed.
const VIEWPORT = { width: 1440, height: 900 } as const;
// When the visual-debug slowdown is on (see lib/slowMode.ts), every
// page/locator action gets a pre-action pause — so the 60s per-test budget
// no longer covers a typical 5–10 action spec. Bump generously when slow.
const slowMoMs = Number(process.env.E2E_SLOWMO ?? 0);
const testTimeout = slowMoMs > 0 ? 600_000 : 60_000;

export default defineConfig({
  testDir: './tests',
  // Sweep orphan e2e-* orgs (left over from teardowns killed mid-flight) before any
  // spec runs — the runner user is on silver tier with a 3-org cap, so a couple of
  // missed teardowns from earlier runs is enough to make every `createOrg` fail 402.
  globalSetup: './lib/globalSetup.ts',
  // 60s accommodates Railway cold-start on idle staging services plus the
  // programmatic-OIDC token roundtrip + initial dashboard render. Bumped to
  // 10min when E2E_SLOWMO is set so the per-action pauses don't blow the budget.
  timeout: testTimeout,
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
      // Viewport override MUST come after the device spread — `devices['Desktop Chrome']`
      // includes its own `viewport: { width: 1280, height: 720 }` which would otherwise win.
      use: { ...devices['Desktop Chrome'], baseURL, viewport: VIEWPORT },
    },
    {
      name: 'landing',
      testDir: './tests/landing',
      use: { ...devices['Desktop Chrome'], baseURL: landingURL, viewport: VIEWPORT },
    },
    {
      name: 'auth',
      testDir: './tests/auth',
      use: { ...devices['Desktop Chrome'], baseURL, viewport: VIEWPORT },
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
  ingestURL:
    process.env.ARGUSLOG_E2E_INGEST_URL ?? 'https://arguslog-ingest-staging.up.railway.app',
  keycloakURL:
    process.env.ARGUSLOG_E2E_KEYCLOAK_URL ?? 'https://arguslog-keycloak-staging.up.railway.app',
  keycloakRealm: process.env.ARGUSLOG_E2E_KEYCLOAK_REALM ?? 'arguslog',
  runnerPAT: process.env.ARGUSLOG_E2E_RUNNER_PAT ?? '',
  runId: process.env.GITHUB_RUN_ID ?? `local-${Date.now().toString(36)}`,
};
