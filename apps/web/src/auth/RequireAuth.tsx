import { Alert, Center, Loader, Stack } from '@mantine/core';
import { useEffect, useRef, useState, type ReactNode } from 'react';
import { useLocation } from 'react-router';

import { useAuthStore } from './useAuthStore';
import { getUserManager } from './userManager';

/**
 * Route guard. Rendered on every protected route — when the auth store says we're
 * unauthenticated, fires Keycloak's signinRedirect directly so the user never sees an
 * intermediate splash page. Idle / loading shows a spinner; we never render protected
 * children with stale auth state.
 *
 * The original URL is stashed in OIDC state so AuthCallbackPage can route the user back
 * where they were after the round-trip.
 */
export function RequireAuth({ children }: { children: ReactNode }) {
  const status = useAuthStore((s) => s.status);
  const signingOut = useAuthStore((s) => s.signingOut);
  const location = useLocation();
  const redirectStartedRef = useRef(false);
  const [redirectError, setRedirectError] = useState<string | null>(null);

  useEffect(() => {
    // signOut() sets signingOut=true synchronously before async-chaining into signoutRedirect.
    // Skipping here means the logout's top-level navigation isn't cancelled by a competing
    // signinRedirect when oidc-client-ts wipes localStorage mid-flight. The page is leaving
    // anyway — KC will redirect us back to "/" once it clears its SSO cookie.
    if (signingOut) return;
    if (status !== 'unauthenticated' && status !== 'error') return;
    if (redirectStartedRef.current) return;
    redirectStartedRef.current = true;

    getUserManager()
      .signinRedirect({ state: { returnTo: location.pathname + location.search } })
      .catch((err: unknown) => {
        // Resetting the ref lets a future status change (e.g. token-renew retry) try again.
        redirectStartedRef.current = false;
        setRedirectError(err instanceof Error ? err.message : 'sign-in failed');
      });
  }, [status, location, signingOut]);

  if (status === 'authenticated') {
    return <>{children}</>;
  }

  if (redirectError) {
    return (
      <Center mih="100vh" p="md">
        <Stack maw={420} gap="sm">
          <Alert color="red" variant="light" title="Sign-in failed">
            {redirectError}
          </Alert>
        </Stack>
      </Center>
    );
  }

  // idle / loading / unauthenticated (redirecting) → spinner only.
  return (
    <Center mih="100vh">
      <Loader size="md" />
    </Center>
  );
}
