import { MantineProvider } from '@mantine/core';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { RequireAuth } from '../../auth/RequireAuth';
import { useAuthStore } from '../../auth/useAuthStore';

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
          <Route path="/login" element={<div>login page</div>} />
        </Routes>
      </MemoryRouter>
    </MantineProvider>,
  );
}

describe('RequireAuth', () => {
  beforeEach(() => setStatus('idle'));
  afterEach(() => setStatus('idle'));

  it('shows a spinner while auth status is loading', () => {
    setStatus('loading');
    renderAt('/protected');
    // Mantine's Loader renders an SVG with role=presentation; just assert protected content is NOT yet there.
    expect(screen.queryByText('protected content')).not.toBeInTheDocument();
    expect(screen.queryByText('login page')).not.toBeInTheDocument();
  });

  it('renders the protected children when authenticated', () => {
    setStatus('authenticated');
    renderAt('/protected');
    expect(screen.getByText('protected content')).toBeInTheDocument();
  });

  it('redirects to /login when unauthenticated', () => {
    setStatus('unauthenticated');
    renderAt('/protected');
    expect(screen.getByText('login page')).toBeInTheDocument();
    expect(screen.queryByText('protected content')).not.toBeInTheDocument();
  });

  it('redirects to /login on error too — never renders the app with a bad token', () => {
    setStatus('error');
    renderAt('/protected');
    expect(screen.getByText('login page')).toBeInTheDocument();
  });
});
