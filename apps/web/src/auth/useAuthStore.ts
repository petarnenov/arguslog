import type { User as OidcUser } from 'oidc-client-ts';
import { create } from 'zustand';

export type AuthStatus = 'idle' | 'loading' | 'authenticated' | 'unauthenticated' | 'error';

export interface AuthUser {
  id: string;
  email?: string;
  name?: string;
}

interface AuthState {
  status: AuthStatus;
  user: AuthUser | null;
  accessToken: string | null;
  expiresAt: number | null;
  error: string | null;
  /**
   * Set synchronously at the start of signOut(), cleared on next page load.
   * Tells RequireAuth not to fire its own signinRedirect while a top-level navigation
   * to Keycloak's end-session endpoint is in flight — without this flag, oidc-client-ts
   * wipes localStorage inside signoutRedirect() and emits userUnloaded → status flips
   * to 'unauthenticated' → RequireAuth's effect schedules a competing signinRedirect →
   * the browser cancels the in-flight logout request (Network shows "canceled") and the
   * second navigation reaches Keycloak with the SSO cookie still alive, so KC issues a
   * fresh code without ever clearing the session. Net effect: user appears instantly
   * re-logged-in.
   */
  signingOut: boolean;

  /** Called on userLoaded — replaces the entire token + user snapshot. */
  setSession: (oidcUser: OidcUser) => void;
  /** Called on userUnloaded / signoutCallback — wipes local state but does NOT touch the IdP. */
  clearSession: () => void;
  setStatus: (status: AuthStatus) => void;
  setError: (message: string) => void;
  setSigningOut: (value: boolean) => void;
}

export const useAuthStore = create<AuthState>((set) => ({
  status: 'idle',
  user: null,
  accessToken: null,
  expiresAt: null,
  error: null,
  signingOut: false,

  setSession: (oidcUser) =>
    set({
      status: 'authenticated',
      user: toUser(oidcUser),
      accessToken: oidcUser.access_token,
      expiresAt: oidcUser.expires_at ?? null,
      error: null,
    }),

  clearSession: () =>
    set({
      status: 'unauthenticated',
      user: null,
      accessToken: null,
      expiresAt: null,
      error: null,
    }),

  setStatus: (status) => set({ status }),

  setError: (message) => set({ status: 'error', error: message }),

  setSigningOut: (value) => set({ signingOut: value }),
}));

function toUser(oidcUser: OidcUser): AuthUser {
  const profile = oidcUser.profile;
  return {
    id: profile.sub,
    email: typeof profile.email === 'string' ? profile.email : undefined,
    name:
      typeof profile.name === 'string'
        ? profile.name
        : typeof profile.preferred_username === 'string'
          ? profile.preferred_username
          : undefined,
  };
}
