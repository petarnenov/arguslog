import { MantineProvider } from '@mantine/core';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import '../../i18n';
import { AlertDestinationsPage } from '../../pages/AlertDestinationsPage';

const originalFetch = globalThis.fetch;

const ORG = { id: 1, slug: 'acme', name: 'Acme', plan: 'free', createdAt: '2026-05-01T00:00:00Z' };

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function renderAt(path = '/orgs/acme/destinations') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <MantineProvider>
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={[path]}>
          <Routes>
            <Route path="/orgs/:orgSlug/destinations" element={<AlertDestinationsPage />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>
    </MantineProvider>,
  );
}

describe('AlertDestinationsPage', () => {
  beforeEach(() => {
    globalThis.fetch = originalFetch;
  });
  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  it('lists destinations the api returns', async () => {
    globalThis.fetch = vi.fn(async (input) => {
      const url = typeof input === 'string' ? input : (input as Request).url;
      if (url.endsWith('/api/v1/orgs')) return jsonResponse([ORG]);
      if (url.endsWith('/api/v1/orgs/1/alert-destinations')) {
        return jsonResponse([
          {
            id: 10,
            orgId: 1,
            kind: 'telegram',
            name: 'ops-chat',
            createdAt: '2026-05-01T00:00:00Z',
          },
          {
            id: 11,
            orgId: 1,
            kind: 'slack',
            name: 'eng-alerts',
            createdAt: '2026-05-01T00:00:00Z',
          },
        ]);
      }
      return jsonResponse([]);
    }) as typeof fetch;

    renderAt();

    await waitFor(() => expect(screen.getByTestId('alert-destinations-table')).toBeInTheDocument());
    expect(screen.getByText('ops-chat')).toBeInTheDocument();
    expect(screen.getByText('eng-alerts')).toBeInTheDocument();
  });

  it('shows the empty state when no destinations exist', async () => {
    globalThis.fetch = vi.fn(async (input) => {
      const url = typeof input === 'string' ? input : (input as Request).url;
      if (url.endsWith('/api/v1/orgs')) return jsonResponse([ORG]);
      return jsonResponse([]);
    }) as typeof fetch;

    renderAt();

    await waitFor(() => expect(screen.getByText(/No destinations yet/i)).toBeInTheDocument());
  });

  it('posts the structured per-kind config when creating a telegram destination', async () => {
    const calls: { url: string; init?: RequestInit }[] = [];
    globalThis.fetch = vi.fn(async (input, init) => {
      const url = typeof input === 'string' ? input : (input as Request).url;
      calls.push({ url, init });
      if (url.endsWith('/api/v1/orgs')) return jsonResponse([ORG]);
      if (url.endsWith('/api/v1/orgs/1/alert-destinations') && (init?.method ?? 'GET') === 'GET') {
        return jsonResponse([]);
      }
      if (url.endsWith('/api/v1/orgs/1/alert-destinations') && init?.method === 'POST') {
        return jsonResponse(
          { id: 99, orgId: 1, kind: 'telegram', name: 'ops', createdAt: '2026-05-01T00:00:00Z' },
          201,
        );
      }
      return jsonResponse([]);
    }) as typeof fetch;

    renderAt();
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: /New destination/i }));
    const nameInput = await screen.findByLabelText(/^Name$/i);
    await user.type(nameInput, 'ops');
    await user.type(screen.getByLabelText(/^Chat ID$/i), '-1001');
    await user.type(screen.getByLabelText(/^Bot token$/i), 'abc:def');
    await user.click(screen.getByRole('button', { name: /^Create$/i }));

    await waitFor(() => {
      const post = calls.find((c) => c.init?.method === 'POST');
      expect(post).toBeDefined();
      const body = JSON.parse(post!.init!.body as string) as Record<string, unknown>;
      expect(body.kind).toBe('telegram');
      expect(body.name).toBe('ops');
      const config = body.config as Record<string, string>;
      expect(config.chatId).toBe('-1001');
      expect(config.botToken).toBe('abc:def');
    });
  });

  it('sends config=null when editing a destination with blank config inputs (rename only)', async () => {
    const calls: { url: string; init?: RequestInit }[] = [];
    globalThis.fetch = vi.fn(async (input, init) => {
      const url = typeof input === 'string' ? input : (input as Request).url;
      calls.push({ url, init });
      if (url.endsWith('/api/v1/orgs')) return jsonResponse([ORG]);
      if (url.endsWith('/api/v1/orgs/1/alert-destinations') && (init?.method ?? 'GET') === 'GET') {
        return jsonResponse([
          { id: 10, orgId: 1, kind: 'slack', name: 'alerts', createdAt: '2026-05-01T00:00:00Z' },
        ]);
      }
      if (url.endsWith('/api/v1/orgs/1/alert-destinations/10') && init?.method === 'PUT') {
        return jsonResponse({
          id: 10,
          orgId: 1,
          kind: 'slack',
          name: 'renamed',
          createdAt: '2026-05-01T00:00:00Z',
        });
      }
      return jsonResponse([]);
    }) as typeof fetch;

    renderAt();
    const user = userEvent.setup();

    await waitFor(() => expect(screen.getByText('alerts')).toBeInTheDocument());
    await user.click(screen.getByRole('button', { name: /Edit alerts/i }));
    const nameInput = await screen.findByLabelText(/^Name$/i);
    await user.clear(nameInput);
    await user.type(nameInput, 'renamed');
    await user.click(screen.getByRole('button', { name: /^Save$/i }));

    await waitFor(() => {
      const put = calls.find((c) => c.init?.method === 'PUT');
      expect(put).toBeDefined();
      const body = JSON.parse(put!.init!.body as string) as Record<string, unknown>;
      expect(body.name).toBe('renamed');
      expect(body.config).toBeNull();
    });
  });
});
