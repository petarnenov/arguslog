import { MantineProvider } from '@mantine/core';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { RequireAuth } from '../../auth/RequireAuth';
import { useAuthStore } from '../../auth/useAuthStore';

// Stub the UserManager so RequireAuth's signinRedirect doesn't actually try to navigate the
// jsdom window to Keycloak. We only assert that it WAS called with the right state — the
// actual navigation is the responsibility of oidc-client-ts.
const signinRedirect = vi.fn(async () => undefined);
vi.mock('../../auth/userManager', () => ({
  getUserManager: () => ({ signinRedirect }),
}));

function setStatus(status: ReturnType<typeof useAuthStore.getState>['status']) {
  useAuthStore.setState({ status, user: null, accessToken: null, expiresAt: null, error: null });
}

function renderAt(initialPath: string) {
  return render(
    <MantineProvider>
      <MemoryRouter initialEntries={[initialPath]}>
        <Routes>
          <Route
            path="/protected"
            element={
              <RequireAuth>
                <div>protected content</div>
              </RequireAuth>
            }
          />
        </Routes>
      </MemoryRouter>
    </MantineProvider>,
  );
}

describe('RequireAuth', () => {
  beforeEach(() => {
    signinRedirect.mockClear();
    setStatus('idle');
  });
  afterEach(() => setStatus('idle'));

  it('shows a spinner while auth status is loading', () => {
    setStatus('loading');
    renderAt('/protected');
    expect(screen.queryByText('protected content')).not.toBeInTheDocument();
    expect(signinRedirect).not.toHaveBeenCalled();
  });

  it('renders the protected children when authenticated', () => {
    setStatus('authenticated');
    renderAt('/protected');
    expect(screen.getByText('protected content')).toBeInTheDocument();
    expect(signinRedirect).not.toHaveBeenCalled();
  });

  it('fires signinRedirect directly when unauthenticated — no /login splash', async () => {
    setStatus('unauthenticated');
    renderAt('/protected');
    // Effect runs after mount; flush the microtask queue.
    await Promise.resolve();
    expect(signinRedirect).toHaveBeenCalledWith(
      expect.objectContaining({ state: { returnTo: '/protected' } }),
    );
    expect(screen.queryByText('protected content')).not.toBeInTheDocument();
  });

  it('also fires signinRedirect on auth error — never renders the app with a bad token', async () => {
    setStatus('error');
    renderAt('/protected');
    await Promise.resolve();
    expect(signinRedirect).toHaveBeenCalled();
  });
});
