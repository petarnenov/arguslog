import { MantineProvider } from '@mantine/core';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import '../../i18n';
import { AlertRulesPage } from '../../pages/AlertRulesPage';

const originalFetch = globalThis.fetch;

const ORG = { id: 1, slug: 'acme', name: 'Acme', plan: 'free', createdAt: '2026-05-01T00:00:00Z' };
const PROJECT = {
  id: 101,
  orgId: 1,
  slug: 'web',
  name: 'Web',
  platform: 'javascript',
  createdAt: '2026-05-01T00:00:00Z',
};
const DESTINATIONS = [
  { id: 10, orgId: 1, kind: 'telegram', name: 'ops-chat', createdAt: '2026-05-01T00:00:00Z' },
];

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function renderAt(path = '/orgs/acme/projects/101/alert-rules') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <MantineProvider>
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={[path]}>
          <Routes>
            <Route
              path="/orgs/:orgSlug/projects/:projectId/alert-rules"
              element={<AlertRulesPage />}
            />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>
    </MantineProvider>,
  );
}

describe('AlertRulesPage', () => {
  beforeEach(() => {
    globalThis.fetch = originalFetch;
  });
  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  it('lists rules and resolves destination ids to badges', async () => {
    globalThis.fetch = vi.fn(async (input) => {
      const url = typeof input === 'string' ? input : (input as Request).url;
      if (url.endsWith('/api/v1/orgs')) return jsonResponse([ORG]);
      if (url.endsWith('/api/v1/orgs/1/projects')) return jsonResponse([PROJECT]);
      if (url.endsWith('/alert-destinations')) return jsonResponse(DESTINATIONS);
      if (url.endsWith('/alert-rules')) {
        return jsonResponse([
          {
            id: 1,
            projectId: 101,
            name: 'errors-in-prod',
            conditions: { level: { in: ['error'] } },
            actions: { destinationIds: [10] },
            throttleSeconds: 600,
            enabled: true,
            createdAt: '2026-05-01T00:00:00Z',
          },
        ]);
      }
      return jsonResponse([]);
    }) as typeof fetch;

    renderAt();

    await waitFor(() => expect(screen.getByTestId('alert-rules-table')).toBeInTheDocument());
    expect(screen.getByText('errors-in-prod')).toBeInTheDocument();
    // Wait for destinations query to resolve and re-render the badge label.
    await waitFor(() => expect(screen.getByText(/ops-chat \(telegram\)/)).toBeInTheDocument());
    expect(screen.getByText('600s')).toBeInTheDocument();
  });

  it('warns the user when no destinations exist', async () => {
    globalThis.fetch = vi.fn(async (input) => {
      const url = typeof input === 'string' ? input : (input as Request).url;
      if (url.endsWith('/api/v1/orgs')) return jsonResponse([ORG]);
      if (url.endsWith('/api/v1/orgs/1/projects')) return jsonResponse([PROJECT]);
      if (url.endsWith('/alert-destinations')) return jsonResponse([]);
      if (url.endsWith('/alert-rules')) return jsonResponse([]);
      return jsonResponse([]);
    }) as typeof fetch;

    renderAt();

    await waitFor(() => expect(screen.getByText(/Add a destination first/i)).toBeInTheDocument());
  });

  it('posts conditions JSON + destination ids on create', async () => {
    const calls: { url: string; init?: RequestInit }[] = [];
    globalThis.fetch = vi.fn(async (input, init) => {
      const url = typeof input === 'string' ? input : (input as Request).url;
      calls.push({ url, init });
      if (url.endsWith('/api/v1/orgs')) return jsonResponse([ORG]);
      if (url.endsWith('/api/v1/orgs/1/projects')) return jsonResponse([PROJECT]);
      if (url.endsWith('/alert-destinations')) return jsonResponse(DESTINATIONS);
      if (url.endsWith('/alert-rules') && (init?.method ?? 'GET') === 'GET') {
        return jsonResponse([]);
      }
      if (url.endsWith('/alert-rules') && init?.method === 'POST') {
        return jsonResponse(
          {
            id: 99,
            projectId: 101,
            name: 'fatal-only',
            conditions: { level: { in: ['fatal'] } },
            actions: { destinationIds: [10] },
            throttleSeconds: 300,
            enabled: true,
            createdAt: '2026-05-05T00:00:00Z',
          },
          201,
        );
      }
      return jsonResponse([]);
    }) as typeof fetch;

    renderAt();
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: /New rule/i }));
    const nameInput = await screen.findByLabelText(/^Name$/i);
    await user.type(nameInput, 'fatal-only');
    // Mantine MultiSelect renders a hidden input + a visible label both bound to the same name —
    // grab the underlying searchbox role to disambiguate.
    await user.click(screen.getByRole('textbox', { name: /^Destinations$/i }));
    await user.click(await screen.findByRole('option', { name: /ops-chat \(telegram\)/i }));
    await user.click(screen.getByRole('button', { name: /^Create$/i }));

    await waitFor(() => {
      const post = calls.find((c) => c.init?.method === 'POST');
      expect(post).toBeDefined();
      const body = JSON.parse(post!.init!.body as string) as Record<string, unknown>;
      expect(body.name).toBe('fatal-only');
      expect((body.actions as { destinationIds: number[] }).destinationIds).toEqual([10]);
      // default throttle
      expect(body.throttleSeconds).toBe(300);
      expect(body.enabled).toBe(true);
    });
  });
});
