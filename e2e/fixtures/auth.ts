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

function buildOidcUserBlobFromPat(pat: string): OidcStoredUser {
  // 10 years out — well past any reasonable E2E run duration.
  const tenYears = 10 * 365 * 24 * 3600;
  // Profile claims are cosmetic for the dashboard's auth state (rendered as the
  // sidebar's "Logged in as …" line). Use a recognisable shape so a screenshot
  // captured during a failure makes it obvious this is an E2E run.
  return {
    access_token: pat,
    expires_at: Math.floor(Date.now() / 1000) + tenYears,
    refresh_token: 'e2e-no-refresh',
    id_token: 'e2e.no.idtoken',
    token_type: 'Bearer',
    scope: 'openid profile email',
    session_state: null,
    profile: {
      sub: '00000000-0000-0000-0000-e2eb0e2eb0e2',
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

  const userBlob = buildOidcUserBlobFromPat(e2eConfig.runnerPAT);

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
