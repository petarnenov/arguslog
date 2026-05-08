import { MantineProvider } from '@mantine/core';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import '../../i18n';
import { useAuthStore } from '../../auth/useAuthStore';
import { OrgSettingsPage } from '../../pages/OrgSettingsPage';

const originalFetch = globalThis.fetch;

const ORG = { id: 1, slug: 'acme', name: 'Acme', plan: 'free', createdAt: '2026-05-01T00:00:00Z' };

const ME_ID = '11111111-1111-1111-1111-111111111111';
const OTHER_ID = '22222222-2222-2222-2222-222222222222';

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function renderAt(path = '/orgs/acme/settings') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <MantineProvider>
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={[path]}>
          <Routes>
            <Route path="/orgs/:orgSlug/settings" element={<OrgSettingsPage />} />
            <Route path="/orgs" element={<div>orgs landing</div>} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>
    </MantineProvider>,
  );
}

describe('OrgSettingsPage', () => {
  beforeEach(() => {
    globalThis.fetch = originalFetch;
    useAuthStore.setState({
      status: 'authenticated',
      user: { id: ME_ID, email: 'me@example.com', name: 'Me' },
      accessToken: 'fake',
      expiresAt: null,
      error: null,
    });
  });
  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  it('lists members and marks the current user', async () => {
    globalThis.fetch = vi.fn(async (input) => {
      const url = typeof input === 'string' ? input : (input as Request).url;
      if (url.endsWith('/api/v1/orgs')) return jsonResponse([ORG]);
      if (url.endsWith('/api/v1/orgs/1/members')) {
        return jsonResponse([
          {
            userId: ME_ID,
            email: 'me@example.com',
            displayName: 'Me',
            role: 'owner',
            addedAt: '2026-05-01T00:00:00Z',
          },
          {
            userId: OTHER_ID,
            email: 'other@example.com',
            displayName: 'Other',
            role: 'member',
            addedAt: '2026-05-02T00:00:00Z',
          },
        ]);
      }
      return jsonResponse([]);
    }) as typeof fetch;

    renderAt();

    await waitFor(() => expect(screen.getByTestId('members-table')).toBeInTheDocument());
    expect(screen.getByText('me@example.com')).toBeInTheDocument();
    expect(screen.getByText('other@example.com')).toBeInTheDocument();
    expect(screen.getByText(/\(you\)/)).toBeInTheDocument();
    // Owner sees the invite button.
    expect(screen.getByRole('button', { name: /Invite member/i })).toBeInTheDocument();
  });

  it('hides the invite button for non-owners', async () => {
    globalThis.fetch = vi.fn(async (input) => {
      const url = typeof input === 'string' ? input : (input as Request).url;
      if (url.endsWith('/api/v1/orgs')) return jsonResponse([ORG]);
      if (url.endsWith('/api/v1/orgs/1/members')) {
        return jsonResponse([
          {
            userId: ME_ID,
            email: 'me@example.com',
            displayName: 'Me',
            role: 'member',
            addedAt: '2026-05-01T00:00:00Z',
          },
        ]);
      }
      return jsonResponse([]);
    }) as typeof fetch;

    renderAt();

    await waitFor(() => expect(screen.getByTestId('members-table')).toBeInTheDocument());
    expect(screen.queryByRole('button', { name: /Invite member/i })).not.toBeInTheDocument();
  });

  it('posts an invite when the form is submitted', async () => {
    const calls: { url: string; method: string; body?: string }[] = [];
    globalThis.fetch = vi.fn(async (input, init) => {
      const url = typeof input === 'string' ? input : (input as Request).url;
      const method = (init?.method ?? 'GET').toUpperCase();
      calls.push({ url, method, body: typeof init?.body === 'string' ? init.body : undefined });
      if (url.endsWith('/api/v1/orgs')) return jsonResponse([ORG]);
      if (url.endsWith('/api/v1/orgs/1/members') && method === 'GET') {
        return jsonResponse([
          {
            userId: ME_ID,
            email: 'me@example.com',
            displayName: 'Me',
            role: 'owner',
            addedAt: '2026-05-01T00:00:00Z',
          },
        ]);
      }
      if (url.endsWith('/api/v1/orgs/1/members') && method === 'POST') {
        return jsonResponse(
          {
            userId: OTHER_ID,
            email: 'new@example.com',
            displayName: null,
            role: 'member',
            addedAt: '2026-05-08T00:00:00Z',
          },
          201,
        );
      }
      return jsonResponse([]);
    }) as typeof fetch;

    renderAt();
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: /Invite member/i }));
    const emailInput = await screen.findByLabelText(/^Email$/i);
    await user.type(emailInput, 'new@example.com');
    await user.click(screen.getByRole('button', { name: /Send invite/i }));

    await waitFor(() => {
      const post = calls.find((c) => c.method === 'POST');
      expect(post).toBeDefined();
      expect(post!.url).toContain('/api/v1/orgs/1/members');
      expect(post!.body).toContain('new@example.com');
      expect(post!.body).toContain('"role":"member"');
    });
  });

  it('rejects bad emails before posting', async () => {
    const calls: string[] = [];
    globalThis.fetch = vi.fn(async (input, init) => {
      const url = typeof input === 'string' ? input : (input as Request).url;
      if ((init?.method ?? 'GET') === 'POST') calls.push(url);
      if (url.endsWith('/api/v1/orgs')) return jsonResponse([ORG]);
      if (url.endsWith('/api/v1/orgs/1/members')) {
        return jsonResponse([
          {
            userId: ME_ID,
            email: 'me@example.com',
            displayName: 'Me',
            role: 'owner',
            addedAt: '2026-05-01T00:00:00Z',
          },
        ]);
      }
      return jsonResponse([]);
    }) as typeof fetch;

    renderAt();
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: /Invite member/i }));
    const emailInput = await screen.findByLabelText(/^Email$/i);
    await user.type(emailInput, 'no-at-sign');
    await user.click(screen.getByRole('button', { name: /Send invite/i }));

    expect(await screen.findByText(/valid email/i)).toBeInTheDocument();
    expect(calls).toHaveLength(0);
  });
});
