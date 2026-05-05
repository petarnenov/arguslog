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
    await getUserManager().signoutRedirect();
  }, []);

  return { status, user, error, signIn, signOut };
}
