import { Center, Loader } from '@mantine/core';
import type { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router';

import { useAuthStore } from './useAuthStore';

/**
 * Route guard. Rendered on every protected route — when the auth store says we're
 * unauthenticated (or errored), redirects to /login carrying the original URL in state so the
 * post-login navigator can put the user back where they were.
 *
 * Idle / loading shows a spinner; we never render protected children with stale auth state.
 */
export function RequireAuth({ children }: { children: ReactNode }) {
  const status = useAuthStore((s) => s.status);
  const location = useLocation();

  if (status === 'idle' || status === 'loading') {
    return (
      <Center mih="100vh">
        <Loader size="md" />
      </Center>
    );
  }

  if (status !== 'authenticated') {
    return <Navigate to="/login" replace state={{ from: location }} />;
  }

  return <>{children}</>;
}
