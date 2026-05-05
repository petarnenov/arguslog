import { useEffect, type ReactNode } from 'react';

import { useAuthStore } from './useAuthStore';
import { getUserManager } from './userManager';

/**
 * Boots the OIDC user manager on mount. Three responsibilities:
 *   1. Restore session from storage (getUser) so a refresh keeps you signed in.
 *   2. Subscribe to userLoaded / userUnloaded so silent renew updates the store.
 *   3. Subscribe to silentRenewError so a stuck refresh surfaces as an error
 *      state instead of looking authenticated but with a dead token.
 *
 * Mounted once at the top of the React tree; multiple mounts are safe but unnecessary.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const setSession = useAuthStore((s) => s.setSession);
  const clearSession = useAuthStore((s) => s.clearSession);
  const setStatus = useAuthStore((s) => s.setStatus);
  const setError = useAuthStore((s) => s.setError);

  useEffect(() => {
    const um = getUserManager();
    let cancelled = false;

    setStatus('loading');
    um.getUser()
      .then((user) => {
        if (cancelled) return;
        if (user && !user.expired) {
          setSession(user);
        } else {
          clearSession();
        }
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        setError(err instanceof Error ? err.message : 'failed to restore session');
      });

    const onLoaded = (user: Parameters<Parameters<typeof um.events.addUserLoaded>[0]>[0]) =>
      setSession(user);
    const onUnloaded = () => clearSession();
    const onSilentRenewError = (err: Error) => setError(err.message || 'silent renew failed');

    um.events.addUserLoaded(onLoaded);
    um.events.addUserUnloaded(onUnloaded);
    um.events.addSilentRenewError(onSilentRenewError);

    return () => {
      cancelled = true;
      um.events.removeUserLoaded(onLoaded);
      um.events.removeUserUnloaded(onUnloaded);
      um.events.removeSilentRenewError(onSilentRenewError);
    };
  }, [setSession, clearSession, setStatus, setError]);

  return <>{children}</>;
}
