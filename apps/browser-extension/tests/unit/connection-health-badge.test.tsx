import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { ConnectionHealthBadge } from '../../src/app/features/connection/ConnectionHealthBadge';
import { getConnectionStatus } from '../../src/shared/domain/connection';
import type { ConnectionStatus } from '../../src/shared/validation/models';

vi.mock('../../src/shared/domain/connection', () => ({
  getConnectionStatus: vi.fn(),
}));

function renderBadge() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <ConnectionHealthBadge />
    </QueryClientProvider>,
  );
}

function mockStatus(authSession: Partial<ConnectionStatus['authSession']>) {
  vi.mocked(getConnectionStatus).mockResolvedValue({
    settings: {
      endpoint: 'https://mcp.arguslog.org/mcp',
      persistenceMode: 'session',
      debug: false,
      theme: 'system',
    },
    authSession: {
      patPresent: true,
      persistenceMode: 'session',
      ...authSession,
    },
    workspaceSelection: { recents: [] },
  } as ConnectionStatus);
}

describe('ConnectionHealthBadge', () => {
  it('renders the ⚪ not-connected state when no health snapshot exists', async () => {
    mockStatus({});
    renderBadge();
    const badge = await screen.findByTestId('connection-health-badge');
    expect(badge.getAttribute('data-state')).toBe('not-connected');
    expect(badge.textContent).toContain('Not connected yet');
  });

  it('renders the 🟢 connected state when lastConnectedAt is set', async () => {
    mockStatus({ lastConnectedAt: new Date(Date.now() - 5_000).toISOString() });
    renderBadge();
    // useQuery resolves async; findByTestId returns the initial-render badge before the
    // data lands. Wait for the data-state attribute to flip from 'not-connected'.
    await waitFor(() => {
      const badge = screen.getByTestId('connection-health-badge');
      expect(badge.getAttribute('data-state')).toBe('connected');
    });
    expect(screen.getByTestId('connection-health-badge').textContent).toContain('Connected');
  });

  it('renders the 🔴 auth-failed state when lastAuthError is the most recent event', async () => {
    mockStatus({
      lastAuthError: {
        code: 'INVALID_PAT',
        httpStatus: 401,
        message: 'Invalid PAT.',
        occurredAt: new Date().toISOString(),
      },
    });
    renderBadge();
    await waitFor(() => {
      const badge = screen.getByTestId('connection-health-badge');
      expect(badge.getAttribute('data-state')).toBe('auth-failed');
    });
    const badge = screen.getByTestId('connection-health-badge');
    expect(badge.textContent).toContain('INVALID_PAT');
    expect(badge.textContent).toContain('HTTP 401');
    expect(badge.textContent).toContain('Invalid PAT.');
  });

  it('prefers the most-recent event when both fields are set (success after failure → 🟢)', async () => {
    const ago = new Date(Date.now() - 60_000).toISOString();
    const now = new Date().toISOString();
    mockStatus({
      lastAuthError: {
        code: 'INVALID_PAT',
        httpStatus: 401,
        message: 'old error',
        occurredAt: ago,
      },
      lastConnectedAt: now,
    });
    renderBadge();
    const badge = await screen.findByTestId('connection-health-badge');
    await waitFor(() => expect(badge.getAttribute('data-state')).toBe('connected'));
  });

  it('prefers the most-recent event when both fields are set (failure after success → 🔴)', async () => {
    const ago = new Date(Date.now() - 60_000).toISOString();
    const now = new Date().toISOString();
    mockStatus({
      lastConnectedAt: ago,
      lastAuthError: {
        code: 'INSUFFICIENT_SCOPE',
        httpStatus: 403,
        message: 'fresh failure',
        occurredAt: now,
      },
    });
    renderBadge();
    const badge = await screen.findByTestId('connection-health-badge');
    await waitFor(() => expect(badge.getAttribute('data-state')).toBe('auth-failed'));
    expect(badge.textContent).toContain('fresh failure');
  });
});
