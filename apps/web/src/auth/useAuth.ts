import { useCallback } from 'react';

import { useAuthStore } from './useAuthStore';
import { getUserManager } from './userManager';

/**
 * Convenience hook for components that need to trigger sign-in / sign-out plus read the
 * current user. Components that only read state can use {@link useAuthStore} directly.
 */
export function useAuth() {
  const status = useAuthStore((s) => s.status);
  const user = useAuthStore((s) => s.user);
  const error = useAuthStore((s) => s.error);

  const signIn = useCallback(async (returnTo?: string) => {
    await getUserManager().signinRedirect({
      state: returnTo ? { returnTo } : undefined,
    });
  }, []);

  const signOut = useCallback(async () => {
    // SYNCHRONOUS first: claim the "navigating to Keycloak end-session" lane so RequireAuth
    // doesn't race us. signoutRedirect() internally wipes localStorage and emits userUnloaded
    // before the browser receives KC's response; without this flag, the resulting
    // status='unauthenticated' makes RequireAuth fire a competing signinRedirect that cancels
    // the in-flight logout request, leaving the KC SSO cookie alive.
    useAuthStore.getState().setSigningOut(true);

    const um = getUserManager();
    const user = await um.getUser();
    const tokenLooksUsable =
      user?.id_token && (!user.expires_at || user.expires_at * 1000 > Date.now());

    if (tokenLooksUsable) {
      try {
        await um.signoutRedirect();
        return;
      } catch {
        // Fall through to local-only sign-out below.
      }
    }
    // No usable id_token (expired, missing, or Keycloak rejected) — log out locally so the user
    // isn't trapped on a "still logged in" UI. The Keycloak SSO cookie may persist; that's OK,
    // the next signinRedirect will reuse it (or prompt fresh) without an error page.
    await um.removeUser();
    window.location.assign(`${window.location.origin}/`);
  }, []);

  return { status, user, error, signIn, signOut };
}
