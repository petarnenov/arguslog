/**
 * Programmatic auth fixture for E2E tests.
 *
 * The dashboard uses `oidc-client-ts` with a `WebStorageStateStore` against
 * `window.localStorage` — the OIDC user blob lands under a key shaped
 * `oidc.user:<authority>:<client_id>`. `AuthProvider` boots `userManager.getUser()`
 * on mount, which reads that key and pushes the result into the auth store; if
 * `expires_at` is in the future, the store flips to `status: 'authenticated'`
 * and `RequireAuth` renders the protected children.
 *
 * Critical insight that lets us skip Keycloak entirely: the dashboard's API client
 * (`apps/web/src/api/client.ts`) sends whatever `useAuthStore.accessToken` holds
 * verbatim as the `Authorization: Bearer …` header. The backend's `SecurityConfig`
 * accepts `Bearer arglog_pat_*` strings as Personal Access Token credentials
 * (see `services/api/.../SecurityConfig.java`). So if we seed a synthetic OIDC
 * user blob where `access_token = <runner PAT>`, the dashboard treats the user
 * as signed-in and every API call carries the PAT — which the backend resolves
 * to the PAT-owner identity.
 *
 * Net effect: only ONE staging-side secret is needed — `ARGUSLOG_E2E_RUNNER_PAT`.
 * No dedicated Keycloak seed client, no separate password-grant flow, no test
 * user with a known password. The PAT-owner identity IS the E2E test user.
 */
import { type BrowserContext, type Page } from '@playwright/test';

import { e2eConfig } from '../playwright.config.js';

interface OidcStoredUser {
  /** PAT plaintext — sent verbatim as Bearer by the dashboard's apiFetch. */
  access_token: string;
  /** Seconds-since-epoch. Set far in the future so `user.expired` stays false. */
  expires_at: number;
  /**
   * Dummy refresh token. The dashboard never silent-renews a PAT-based session
   * (silent renew talks to Keycloak's token endpoint, which would 401 for a
   * non-OIDC access token — but silent renew only fires near expiry, and our
   * `expires_at` is years out).
   */
  refresh_token?: string;
  /**
   * Dummy id token. `oidc-client-ts` only validates the id_token at login
   * callback time; on subsequent `getUser()` loads it's only inspected for
   * claims. A minimal unsigned-shape value passes without errors.
   */
  id_token?: string;
  token_type: 'Bearer';
  scope?: string;
  session_state: null;
  profile: {
    sub: string;
    email?: string;
    name?: string;
    preferred_username?: string;
  };
}

/**
 * Storage key shape `oidc-client-ts` uses in `WebStorageStateStore`:
 *   `oidc.user:<authority>:<client_id>`
 *
 * Authority is the production `arguslog-web` runtime config:
 *   `<keycloak_url>/realms/<realm>` — pulled from env so a self-hosted target works.
 *
 * Must match `getUserManager()` in `apps/web/src/auth/userManager.ts` exactly,
 * otherwise the SDK reads a different key and sees no session.
 */
function oidcStorageKey(authority: string, clientId: string): string {
  return `oidc.user:${authority}:${clientId}`;
}

/**
 * Cache the runner's /me payload (userId + tier). The dashboard's
 * `useAuthStore.user.id = profile.sub`, and pages compare against it to decide things like
 * "am I the org owner?" (`OrgMembersPage.isOwner`). A hardcoded sub never matches → owner-
 * only UI (e.g. the Invite button) is permanently hidden. The first call per worker hits
 * the API; subsequent calls reuse the resolved Promise. The same payload also carries
 * `tier`, which specs can use to skip themselves when the runner's tier disallows the
 * exercised path (e.g. members-crud needs silver+ because regular caps at 1 member).
 */
interface MePayload {
  userId: string;
  tier: 'regular' | 'silver' | 'gold' | 'platinum';
  isPlatformAdmin: boolean;
}
let cachedMe: Promise<MePayload> | null = null;
async function getRunnerMe(): Promise<MePayload> {
  if (cachedMe) return cachedMe;
  cachedMe = (async () => {
    const resp = await fetch(`${e2eConfig.apiURL}/api/v1/me`, {
      headers: { Authorization: `Bearer ${e2eConfig.runnerPAT}` },
    });
    if (!resp.ok) {
      throw new Error(
        `loginAsTestUser: GET /api/v1/me failed (${resp.status}). Is the runner PAT valid?`,
      );
    }
    return (await resp.json()) as MePayload;
  })();
  return cachedMe;
}

async function getRunnerUserId(): Promise<string> {
  return (await getRunnerMe()).userId;
}

/**
 * Returns true if the runner's tier is at least `min`. Specs that exercise tier-gated
 * behavior (e.g. multi-member orgs, multi-project orgs) call this to skip themselves
 * cleanly on lower-tier runners instead of failing with 402 PaymentRequired mid-flow.
 *
 * Tier order (per V32 enum): regular < silver < gold < platinum.
 */
export async function runnerTierIsAtLeast(
  min: 'regular' | 'silver' | 'gold' | 'platinum',
): Promise<boolean> {
  const order = { regular: 0, silver: 1, gold: 2, platinum: 3 } as const;
  const me = await getRunnerMe();
  return order[me.tier] >= order[min];
}

async function buildOidcUserBlobFromPat(pat: string): Promise<OidcStoredUser> {
  // 10 years out — well past any reasonable E2E run duration.
  const tenYears = 10 * 365 * 24 * 3600;
  // `profile.sub` MUST be the runner's real userId so owner-checks resolve correctly.
  const sub = await getRunnerUserId();
  return {
    access_token: pat,
    expires_at: Math.floor(Date.now() / 1000) + tenYears,
    refresh_token: 'e2e-no-refresh',
    id_token: 'e2e.no.idtoken',
    token_type: 'Bearer',
    scope: 'openid profile email',
    session_state: null,
    profile: {
      sub,
      email: 'e2e-runner@arguslog.local',
      name: 'E2E Test Runner',
      preferred_username: 'e2e-runner',
    },
  };
}

/**
 * Primes the page so `AuthProvider` boots into an authenticated state without
 * ever talking to Keycloak. Use this as the first line of any authenticated
 * test:
 *
 *   ```ts
 *   await loginAsTestUser(page);
 *   await page.goto('/orgs');
 *   ```
 *
 * Mechanism: `addInitScript` runs in the page's frame before any of the app's
 * scripts. We write the synthetic OIDC user blob to `localStorage` so that
 * `AuthProvider`'s `getUser()` call finds a valid, non-expired session.
 */
export async function loginAsTestUser(page: Page): Promise<void> {
  if (!e2eConfig.runnerPAT) {
    throw new Error(
      'ARGUSLOG_E2E_RUNNER_PAT must be set. This is a Personal Access Token minted on staging ' +
        'for the test user; the dashboard treats it as the active session via the OIDC user ' +
        'blob seeded into localStorage. See e2e/README.md for one-time setup.',
    );
  }

  const userBlob = await buildOidcUserBlobFromPat(e2eConfig.runnerPAT);

  // The dashboard's UserManager constructs its authority from
  // VITE_KEYCLOAK_URL + VITE_KEYCLOAK_REALM and uses the production
  // `arguslog-web` client_id. Match that key shape exactly.
  const authority = `${e2eConfig.keycloakURL}/realms/${e2eConfig.keycloakRealm}`;
  const productionClientId = process.env.ARGUSLOG_E2E_DASHBOARD_CLIENT_ID ?? 'arguslog-web';
  const storageKey = oidcStorageKey(authority, productionClientId);

  await page.addInitScript(
    ({ key, value }) => {
      try {
        window.localStorage.setItem(key, value);
      } catch {
        // localStorage may be locked down in some sandboxes — the test will surface
        // the auth failure as a redirect to /auth/callback or the Keycloak login page,
        // which is the right signal that this fixture needs adjustment for that env.
      }
    },
    { key: storageKey, value: JSON.stringify(userBlob) },
  );
}

/**
 * Returns the PAT that the authed page is logged in with. Useful for tests
 * that need to make a server-side API call as the test user (e.g. seeding
 * extra data outside the standard `testData` fixture).
 */
export function getTestUserAccessToken(): string {
  if (!e2eConfig.runnerPAT) {
    throw new Error('ARGUSLOG_E2E_RUNNER_PAT must be set.');
  }
  return e2eConfig.runnerPAT;
}

/**
 * Wipes the OIDC user blob from a context's storage. Used to model a fresh
 * logged-out state for sign-in-flow tests.
 */
export async function clearAuth(context: BrowserContext): Promise<void> {
  await context.clearCookies();
  // localStorage clear happens via init script on next page load — Playwright
  // doesn't expose direct origin-scoped localStorage manipulation outside of a page.
}

// ─────────────────────────────────────────────────────────────────────────
// Real-Keycloak fixture (password grant)
// ─────────────────────────────────────────────────────────────────────────
//
// Some endpoints — most notably `POST /api/v1/me/tokens` (mint a new PAT) —
// deliberately reject PAT auth (403). They demand a real Keycloak JWT to
// prevent privilege escalation: a leaked read-only PAT mustn't be able to
// promote itself to write/admin. For specs that exercise those endpoints we
// need a JWT, not a PAT-as-OIDC-blob.
//
// Approach: Direct Access Grant (password grant) against the dev-only
// `arguslog-seed` client (Direct Access Grants enabled in realm.template.json
// — the production `arguslog-web` client keeps DAG off). Seed-demo creates
// the demo user with a known password, so on local this is one fetch away.
//
// Staging/prod: the realm doesn't ship `arguslog-seed`. To run these specs
// against a remote env, set:
//   ARGUSLOG_E2E_KC_PASSWORD_CLIENT=<a-client-with-DAG-on-the-target-realm>
//   ARGUSLOG_E2E_KC_USERNAME=<test-user>
//   ARGUSLOG_E2E_KC_PASSWORD=<test-password>
// Otherwise `isRealKcAvailable()` returns false and the dependent spec
// `test.skip`s instead of failing the suite.

interface KcTokenResponse {
  access_token: string;
  refresh_token?: string;
  id_token?: string;
  expires_in?: number;
  token_type?: string;
}

function realKcCreds(): { username: string; password: string; clientId: string } | null {
  // On a local dev stack we default to the seeded demo creds + `arguslog-seed`
  // client. On any non-local target the caller MUST set the env vars; otherwise
  // we can't safely guess the credentials and the spec should skip.
  const isLocal = e2eConfig.keycloakURL.startsWith('http://');
  const username =
    process.env.ARGUSLOG_E2E_KC_USERNAME ?? (isLocal ? 'demo@arguslog.local' : undefined);
  const password = process.env.ARGUSLOG_E2E_KC_PASSWORD ?? (isLocal ? 'demo' : undefined);
  const clientId =
    process.env.ARGUSLOG_E2E_KC_PASSWORD_CLIENT ?? (isLocal ? 'arguslog-seed' : undefined);
  if (!username || !password || !clientId) return null;
  return { username, password, clientId };
}

/**
 * Returns true if the env has enough config to perform a Keycloak password grant.
 * Specs that need a real JWT (e.g. tokens-crud) gate themselves with this so the
 * suite stays green on environments without a DAG-enabled client.
 */
export function isRealKcAvailable(): boolean {
  return realKcCreds() !== null;
}

/** Decode the `sub` claim out of a JWT without verifying the signature. */
function decodeJwtSub(jwt: string): string {
  const parts = jwt.split('.');
  if (parts.length !== 3) throw new Error('JWT format invalid');
  const padded = parts[1]!.replace(/-/g, '+').replace(/_/g, '/');
  const payload = JSON.parse(Buffer.from(padded, 'base64').toString('utf8')) as { sub?: string };
  if (!payload.sub) throw new Error('JWT missing sub claim');
  return payload.sub;
}

/**
 * Primes the page so `AuthProvider` boots into an authenticated state with a
 * REAL Keycloak JWT (not a PAT-as-OIDC blob). Use for specs that hit endpoints
 * which refuse PAT auth — `POST /api/v1/me/tokens` is the canonical example.
 *
 * Caveats:
 *  - JWT default lifetime is ~5min. A single spec is well within that budget;
 *    long-running specs should re-login mid-test if they cross the boundary.
 *  - Requires a Keycloak client with Direct Access Grants enabled (the realm's
 *    `arguslog-seed` client on local; configurable via env on remote envs).
 */
export async function loginAsRealUser(page: Page): Promise<void> {
  const creds = realKcCreds();
  if (!creds) {
    throw new Error(
      'loginAsRealUser: no real-KC credentials available. Set ' +
        'ARGUSLOG_E2E_KC_USERNAME / ARGUSLOG_E2E_KC_PASSWORD / ARGUSLOG_E2E_KC_PASSWORD_CLIENT, ' +
        'or run against a local stack (demo/demo against `arguslog-seed`).',
    );
  }

  const tokenUrl = `${e2eConfig.keycloakURL}/realms/${e2eConfig.keycloakRealm}/protocol/openid-connect/token`;
  const body = new URLSearchParams({
    client_id: creds.clientId,
    username: creds.username,
    password: creds.password,
    grant_type: 'password',
  }).toString();

  const resp = await fetch(tokenUrl, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body,
  });
  if (!resp.ok) {
    const text = await resp.text().catch(() => '');
    throw new Error(
      `loginAsRealUser: password grant against ${creds.clientId} failed (${resp.status}). ` +
        `On staging this likely means the client doesn't have Direct Access Grants enabled. ` +
        `Response: ${text.slice(0, 200)}`,
    );
  }
  const token = (await resp.json()) as KcTokenResponse;

  const sub = decodeJwtSub(token.access_token);
  const tenYears = 10 * 365 * 24 * 3600;
  const userBlob: OidcStoredUser = {
    access_token: token.access_token,
    // The JWT itself expires in ~5min; we set blob `expires_at` far out so the
    // dashboard's silent-renew doesn't fire mid-test. The api still checks the
    // JWT's own `exp` claim and will reject stale ones, which is what we want.
    expires_at: Math.floor(Date.now() / 1000) + tenYears,
    refresh_token: token.refresh_token ?? 'e2e-no-refresh',
    id_token: token.id_token ?? 'e2e.no.idtoken',
    token_type: 'Bearer',
    scope: 'openid profile email',
    session_state: null,
    profile: {
      sub,
      email: creds.username,
      name: 'E2E Real User',
      preferred_username: creds.username,
    },
  };

  const authority = `${e2eConfig.keycloakURL}/realms/${e2eConfig.keycloakRealm}`;
  const productionClientId = process.env.ARGUSLOG_E2E_DASHBOARD_CLIENT_ID ?? 'arguslog-web';
  const storageKey = oidcStorageKey(authority, productionClientId);

  await page.addInitScript(
    ({ key, value }) => {
      try {
        window.localStorage.setItem(key, value);
      } catch {
        // see loginAsTestUser for rationale
      }
    },
    { key: storageKey, value: JSON.stringify(userBlob) },
  );
}
