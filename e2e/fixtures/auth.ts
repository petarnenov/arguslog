/**
 * Programmatic auth fixture for E2E tests.
 *
 * The dashboard uses `oidc-client-ts` with a `WebStorageStateStore` against
 * `window.localStorage` — the OIDC user blob lands under a key shaped
 * `oidc.user:<authority>:<client_id>`. Rather than scripting the full browser
 * redirect dance through Keycloak's login form (slow, fragile in CI), we mint
 * an access token via the Direct Access Grants (password) flow against the
 * `arguslog-seed` client and seed the resulting User blob into localStorage
 * before the dashboard boots.
 *
 * Pre-requisites on the target environment (one-time manual setup, documented
 * in `e2e/README.md`):
 *   1. The realm has the `arguslog-seed` client with Direct Access Grants
 *      enabled and an audience mapper that includes `arguslog-web`.
 *   2. A dedicated E2E user exists (`e2e@arguslog.local` by default) with a
 *      known password.
 *   3. Env vars `ARGUSLOG_E2E_TEST_USER_EMAIL` + `ARGUSLOG_E2E_TEST_USER_PASSWORD`
 *      are set in the test runner.
 */
import { type Page, type BrowserContext } from '@playwright/test';

import { e2eConfig } from '../playwright.config.js';

interface OidcTokenResponse {
  access_token: string;
  expires_in: number;
  refresh_token?: string;
  id_token?: string;
  token_type: 'Bearer';
  scope?: string;
}

interface OidcStoredUser {
  /** Matches oidc-client-ts `User` shape exactly so the SDK reads it back without complaint. */
  access_token: string;
  expires_at: number;
  refresh_token?: string;
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

let cachedToken: OidcTokenResponse | undefined;
let cachedAt = 0;

/**
 * Exchanges the test-user creds for an access token via Keycloak's password grant.
 * Cached for 30s so repeated `loginAsTestUser` calls within a single test don't hammer KC.
 */
export async function getTestUserToken(): Promise<OidcTokenResponse> {
  const ageMs = Date.now() - cachedAt;
  if (cachedToken && ageMs < 30_000) return cachedToken;

  if (!e2eConfig.testUserEmail || !e2eConfig.testUserPassword) {
    throw new Error(
      'ARGUSLOG_E2E_TEST_USER_EMAIL and ARGUSLOG_E2E_TEST_USER_PASSWORD must be set. ' +
        'See e2e/README.md for staging-side setup.',
    );
  }

  const url = `${e2eConfig.keycloakURL}/realms/${e2eConfig.keycloakRealm}/protocol/openid-connect/token`;
  const body = new URLSearchParams({
    client_id: e2eConfig.keycloakClientId,
    username: e2eConfig.testUserEmail,
    password: e2eConfig.testUserPassword,
    grant_type: 'password',
    scope: 'openid profile email',
  });
  const resp = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body,
  });
  if (!resp.ok) {
    const text = await resp.text().catch(() => '');
    throw new Error(
      `Keycloak password-grant failed: ${resp.status} ${resp.statusText}${
        text ? ` — ${text.slice(0, 300)}` : ''
      }. ` +
        'Verify the seed client exists on staging and the test user is set up per e2e/README.md.',
    );
  }
  cachedToken = (await resp.json()) as OidcTokenResponse;
  cachedAt = Date.now();
  return cachedToken;
}

/**
 * Decodes the access token's `sub` + `email` + `name` claims so the seeded
 * user blob in localStorage matches the shape oidc-client-ts produces after
 * its own callback. No signature check — we're not validating, just reading.
 */
function decodeJwtPayload(jwt: string): {
  sub: string;
  email?: string;
  name?: string;
  preferred_username?: string;
} {
  const [, payload] = jwt.split('.');
  if (!payload) throw new Error('JWT missing payload segment');
  // Base64-url-decode (replace - with +, _ with /, pad to multiple of 4)
  const b64 = payload.replace(/-/g, '+').replace(/_/g, '/');
  const padded = b64 + '='.repeat((4 - (b64.length % 4)) % 4);
  const json = Buffer.from(padded, 'base64').toString('utf8');
  return JSON.parse(json);
}

function buildOidcUserBlob(token: OidcTokenResponse): OidcStoredUser {
  const claims = decodeJwtPayload(token.access_token);
  return {
    access_token: token.access_token,
    expires_at: Math.floor(Date.now() / 1000) + token.expires_in,
    refresh_token: token.refresh_token,
    id_token: token.id_token,
    token_type: 'Bearer',
    scope: token.scope ?? 'openid profile email',
    session_state: null,
    profile: {
      sub: claims.sub,
      email: claims.email,
      name: claims.name,
      preferred_username: claims.preferred_username,
    },
  };
}

/**
 * Storage key oidc-client-ts uses to persist the User in WebStorageStateStore.
 * Shape: `oidc.user:<authority>:<client_id>` where authority is
 * `<keycloak_url>/realms/<realm>` — must match the runtime `getUserManager()`
 * config in `apps/web/src/auth/userManager.ts`. The dashboard's keycloak
 * env vars (VITE_KEYCLOAK_URL + VITE_KEYCLOAK_REALM + VITE_KEYCLOAK_CLIENT_ID)
 * are the staging ones (the public client `arguslog-web`), not the seed
 * client we minted the token with — both clients live in the same realm,
 * so the access token is accepted by the API regardless of which client
 * minted it (assuming the audience mapper is configured).
 */
function oidcStorageKey(authority: string, clientId: string): string {
  return `oidc.user:${authority}:${clientId}`;
}

/**
 * Seeds the OIDC user blob into the page's localStorage BEFORE any app code
 * runs. Use this as the first line of any authenticated test:
 *
 *   ```ts
 *   await loginAsTestUser(page);
 *   await page.goto('/orgs');
 *   ```
 */
export async function loginAsTestUser(page: Page): Promise<void> {
  const token = await getTestUserToken();
  const userBlob = buildOidcUserBlob(token);

  // The dashboard's UserManager is built with the production `arguslog-web`
  // client_id, so localStorage must persist under that key — not the seed
  // client we used to mint the token. The realm + authority host stays the
  // same (only client_id differs).
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
 * Returns the same access token a logged-in browser would carry — useful when an
 * API call needs to authenticate as the test user (e.g. for setup that the runner
 * PAT can't do on the user's behalf).
 */
export async function getTestUserAccessToken(): Promise<string> {
  const token = await getTestUserToken();
  return token.access_token;
}

/**
 * Drops the auth fixture's token cache. Call between distinct user sessions in
 * the same test file — rare, since most specs use the same fixture across tests.
 */
export function resetAuthCache(): void {
  cachedToken = undefined;
  cachedAt = 0;
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
