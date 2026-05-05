import { UserManager, WebStorageStateStore } from 'oidc-client-ts';

import { env } from '../env';

/**
 * One UserManager per browser tab. Singleton on purpose: oidc-client-ts handles silent renew
 * and event subscriptions internally, and constructing more than one would race on the same
 * tokens in localStorage.
 *
 * Test seam: tests can replace this via `__setUserManagerForTests`. Not exported in production
 * builds beyond the seam name to discourage misuse.
 */
let instance: UserManager | undefined;

export function getUserManager(): UserManager {
  if (!instance) {
    instance = new UserManager({
      authority: `${env.VITE_KEYCLOAK_URL}/realms/${env.VITE_KEYCLOAK_REALM}`,
      client_id: env.VITE_KEYCLOAK_CLIENT_ID,
      redirect_uri: `${window.location.origin}/auth/callback`,
      post_logout_redirect_uri: `${window.location.origin}/login`,
      response_type: 'code',
      scope: 'openid profile email',
      loadUserInfo: true,
      automaticSilentRenew: true,
      // Tokens in localStorage: visible to XSS, but the SDK assumes a SPA-with-PKCE stance —
      // refresh tokens are short-lived and the IdP can revoke. If we tighten threat model
      // later, swap for in-memory + a /auth refresh route on the api.
      userStore: new WebStorageStateStore({ store: window.localStorage }),
    });
  }
  return instance;
}

/** Test-only: install a stub UserManager. Resets to lazy-created singleton when called with undefined. */
export function __setUserManagerForTests(stub: UserManager | undefined): void {
  instance = stub;
}
