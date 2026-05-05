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

  /** Called on userLoaded — replaces the entire token + user snapshot. */
  setSession: (oidcUser: OidcUser) => void;
  /** Called on userUnloaded / signoutCallback — wipes local state but does NOT touch the IdP. */
  clearSession: () => void;
  setStatus: (status: AuthStatus) => void;
  setError: (message: string) => void;
}

export const useAuthStore = create<AuthState>((set) => ({
  status: 'idle',
  user: null,
  accessToken: null,
  expiresAt: null,
  error: null,

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
