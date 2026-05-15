import type { User as OidcUser } from 'oidc-client-ts';
import { beforeEach, describe, expect, it } from 'vitest';

import { useAuthStore } from '../../auth/useAuthStore';

function fakeOidcUser(overrides: Partial<OidcUser> = {}): OidcUser {
  return {
    access_token: 'tok-123',
    expires_at: 1730000000,
    profile: {
      sub: '00000000-0000-0000-0000-000000000001',
      email: 'alice@example.com',
      name: 'Alice',
    },
    ...overrides,
  } as unknown as OidcUser;
}

describe('useAuthStore', () => {
  beforeEach(() => {
    useAuthStore.setState({
      status: 'idle',
      user: null,
      accessToken: null,
      expiresAt: null,
      error: null,
      signingOut: false,
    });
  });

  it('starts idle with no user', () => {
    const s = useAuthStore.getState();
    expect(s.status).toBe('idle');
    expect(s.user).toBeNull();
    expect(s.accessToken).toBeNull();
  });

  it('setSession populates user + token + status from the OIDC profile', () => {
    useAuthStore.getState().setSession(fakeOidcUser());
    const s = useAuthStore.getState();
    expect(s.status).toBe('authenticated');
    expect(s.user).toEqual({
      id: '00000000-0000-0000-0000-000000000001',
      email: 'alice@example.com',
      name: 'Alice',
    });
    expect(s.accessToken).toBe('tok-123');
    expect(s.expiresAt).toBe(1730000000);
    expect(s.error).toBeNull();
  });

  it('setSession falls back to preferred_username when name is missing', () => {
    useAuthStore.getState().setSession(
      fakeOidcUser({
        profile: {
          sub: 'abc',
          preferred_username: 'alice',
        } as unknown as OidcUser['profile'],
      }),
    );
    expect(useAuthStore.getState().user?.name).toBe('alice');
  });

  it('clearSession wipes everything to unauthenticated, never to idle', () => {
    useAuthStore.getState().setSession(fakeOidcUser());
    useAuthStore.getState().clearSession();
    const s = useAuthStore.getState();
    expect(s.status).toBe('unauthenticated');
    expect(s.user).toBeNull();
    expect(s.accessToken).toBeNull();
    expect(s.expiresAt).toBeNull();
  });

  it('setError surfaces an error and parks status', () => {
    useAuthStore.getState().setError('boom');
    const s = useAuthStore.getState();
    expect(s.status).toBe('error');
    expect(s.error).toBe('boom');
  });

  it('setSigningOut toggles the signingOut flag without touching other state', () => {
    useAuthStore.getState().setSession(fakeOidcUser());
    useAuthStore.getState().setSigningOut(true);
    expect(useAuthStore.getState().signingOut).toBe(true);
    expect(useAuthStore.getState().status).toBe('authenticated');
    useAuthStore.getState().setSigningOut(false);
    expect(useAuthStore.getState().signingOut).toBe(false);
  });
});
