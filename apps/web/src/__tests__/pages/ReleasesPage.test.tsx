import { MantineProvider } from '@mantine/core';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import '../../i18n';
import { ReleasesPage } from '../../pages/ReleasesPage';

const originalFetch = globalThis.fetch;

const ORG = { id: 1, slug: 'acme', name: 'Acme', plan: 'free', createdAt: '2026-05-01T00:00:00Z' };

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function renderAt(path = '/orgs/acme/projects/101/releases') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <MantineProvider>
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={[path]}>
          <Routes>
            <Route path="/orgs/:orgSlug/projects/:projectId/releases" element={<ReleasesPage />} />
            <Route path="/orgs/:orgSlug/projects" element={<div>projects landing</div>} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>
    </MantineProvider>,
  );
}

describe('ReleasesPage', () => {
  beforeEach(() => {
    globalThis.fetch = originalFetch;
  });
  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  it('lists releases the api returns', async () => {
    globalThis.fetch = vi.fn(async (input) => {
      const url = typeof input === 'string' ? input : (input as Request).url;
      if (url.endsWith('/api/v1/orgs')) return jsonResponse([ORG]);
      if (url.endsWith('/api/v1/projects/101/releases')) {
        return jsonResponse([
          { id: 7, projectId: 101, version: '1.2.3', createdAt: '2026-05-08T12:00:00Z' },
          { id: 6, projectId: 101, version: '1.2.2', createdAt: '2026-05-07T10:00:00Z' },
        ]);
      }
      return jsonResponse([]);
    }) as typeof fetch;

    renderAt();

    await waitFor(() => expect(screen.getByTestId('releases-table')).toBeInTheDocument());
    expect(screen.getByText('1.2.3')).toBeInTheDocument();
    expect(screen.getByText('1.2.2')).toBeInTheDocument();
  });

  it('shows the empty state when no releases exist', async () => {
    globalThis.fetch = vi.fn(async (input) => {
      const url = typeof input === 'string' ? input : (input as Request).url;
      if (url.endsWith('/api/v1/orgs')) return jsonResponse([ORG]);
      return jsonResponse([]);
    }) as typeof fetch;

    renderAt();

    await waitFor(() => expect(screen.getByText(/No releases yet/i)).toBeInTheDocument());
  });

  it('posts the typed version when the user submits the create form', async () => {
    const calls: { url: string; method: string; body?: string }[] = [];
    globalThis.fetch = vi.fn(async (input, init) => {
      const url = typeof input === 'string' ? input : (input as Request).url;
      const method = (init?.method ?? 'GET').toUpperCase();
      calls.push({ url, method, body: typeof init?.body === 'string' ? init.body : undefined });
      if (url.endsWith('/api/v1/orgs')) return jsonResponse([ORG]);
      if (url.endsWith('/api/v1/projects/101/releases') && method === 'GET') {
        return jsonResponse([]);
      }
      if (url.endsWith('/api/v1/projects/101/releases') && method === 'POST') {
        return jsonResponse(
          { id: 9, projectId: 101, version: '2.0.0', createdAt: '2026-05-08T13:00:00Z' },
          201,
        );
      }
      return jsonResponse([]);
    }) as typeof fetch;

    renderAt();
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: /New release/i }));
    const versionInput = await screen.findByLabelText(/^Version$/i);
    await user.type(versionInput, '2.0.0');
    await user.click(screen.getByRole('button', { name: /^Create$/i }));

    await waitFor(() => {
      const post = calls.find((c) => c.method === 'POST');
      expect(post).toBeDefined();
      expect(post!.url).toContain('/api/v1/projects/101/releases');
      expect(post!.body).toContain('"version":"2.0.0"');
    });
  });

  it('rejects empty version client-side', async () => {
    const calls: string[] = [];
    globalThis.fetch = vi.fn(async (input, init) => {
      const url = typeof input === 'string' ? input : (input as Request).url;
      if ((init?.method ?? 'GET') === 'POST') calls.push(url);
      if (url.endsWith('/api/v1/orgs')) return jsonResponse([ORG]);
      return jsonResponse([]);
    }) as typeof fetch;

    renderAt();
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: /New release/i }));
    // Modal renders into a portal — wait for the submit button before clicking.
    await user.click(await screen.findByRole('button', { name: /^Create$/i }));

    expect(await screen.findByText(/Version is required/i)).toBeInTheDocument();
    expect(calls).toHaveLength(0);
  });
});
