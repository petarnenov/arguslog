import { MantineProvider } from '@mantine/core';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import '../../i18n';
import { ProjectKeysPage } from '../../pages/ProjectKeysPage';

const originalFetch = globalThis.fetch;

function renderAt(path: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <MantineProvider>
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={[path]}>
          <Routes>
            <Route
              path="/orgs/:orgSlug/projects/:projectId/settings/keys"
              element={<ProjectKeysPage />}
            />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>
    </MantineProvider>,
  );
}

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function problemResponse(detail: string, status = 409, type?: string) {
  return new Response(
    JSON.stringify({
      type: type ?? 'https://arguslog.org/problems/dsn-already-revoked',
      title: 'Conflict',
      detail,
    }),
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  );
}

const KEY_A = {
  id: 101,
  projectId: 9,
  dsnPublic: 'PUBKEYAAAAAAAAAAAAAAAAAAAAAAAAAA',
  active: true,
  createdAt: '2026-04-01T00:00:00Z',
};
const KEY_B = {
  id: 102,
  projectId: 9,
  dsnPublic: 'PUBKEYBBBBBBBBBBBBBBBBBBBBBBBBBB',
  active: true,
  createdAt: '2026-05-01T00:00:00Z',
};
const FRESH_KEY = {
  ...KEY_B,
  id: 103,
  dsnPublic: 'FRESHKEYxxxxxxxxxxxxxxxxxxxxxxxx',
  dsn: 'arguslog://FRESHKEYxxxxxxxxxxxxxxxxxxxxxxxx@localhost:8080/api/9',
  createdAt: '2026-05-08T12:00:00Z',
};

describe('ProjectKeysPage', () => {
  beforeEach(() => {
    globalThis.fetch = originalFetch;
  });
  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  it('lists active keys with metadata only and never renders the full DSN string', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === 'string' ? input : input.toString();
      const method = init?.method ?? 'GET';
      if (url.endsWith('/api/v1/projects/9/keys') && method === 'GET') {
        return jsonResponse([KEY_B, KEY_A]);
      }
      throw new Error(`unexpected ${method} ${url}`);
    });
    globalThis.fetch = fetchMock as typeof fetch;

    renderAt('/orgs/acme/projects/9/settings/keys');

    await screen.findByText(KEY_A.dsnPublic);
    await screen.findByText(KEY_B.dsnPublic);
    // Full DSN string format must never appear in the listing — only the public-key chunk.
    expect(screen.queryByText(/arguslog:\/\//)).not.toBeInTheDocument();
  });

  it('generates a key and shows the full DSN exactly once in the reveal modal', async () => {
    let listCalls = 0;
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === 'string' ? input : input.toString();
      const method = init?.method ?? 'GET';
      if (url.endsWith('/api/v1/projects/9/keys') && method === 'GET') {
        listCalls += 1;
        // First call: empty list. After create + invalidate: one row (the freshly minted).
        return jsonResponse(listCalls === 1 ? [] : [FRESH_KEY]);
      }
      if (url.endsWith('/api/v1/projects/9/keys') && method === 'POST') {
        return jsonResponse(FRESH_KEY, 201);
      }
      throw new Error(`unexpected ${method} ${url}`);
    });
    globalThis.fetch = fetchMock as typeof fetch;

    renderAt('/orgs/acme/projects/9/settings/keys');
    const user = userEvent.setup();

    await screen.findByText(/No active keys yet/i);

    await user.click(screen.getByRole('button', { name: /Generate new key/i }));

    // Modal shows the full DSN once.
    await waitFor(() => {
      expect(screen.getByText(FRESH_KEY.dsn)).toBeInTheDocument();
    });
    expect(screen.getByText(/only time the full DSN/i)).toBeInTheDocument();

    // Acknowledge — modal closes and listing has refetched (now showing the new key as a row).
    await user.click(screen.getByRole('button', { name: /I've saved the DSN/i }));
    await waitFor(() => {
      expect(screen.queryByText(FRESH_KEY.dsn)).not.toBeInTheDocument();
    });
    await screen.findByText(FRESH_KEY.dsnPublic);
  });

  it('revokes a key after confirmation and refetches the list', async () => {
    let revokeCalls = 0;
    let listCalls = 0;
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === 'string' ? input : input.toString();
      const method = init?.method ?? 'GET';
      if (url.endsWith('/api/v1/projects/9/keys') && method === 'GET') {
        listCalls += 1;
        return jsonResponse(listCalls === 1 ? [KEY_A] : []);
      }
      if (url.endsWith(`/api/v1/projects/9/keys/${KEY_A.id}`) && method === 'DELETE') {
        revokeCalls += 1;
        return new Response(null, { status: 204 });
      }
      throw new Error(`unexpected ${method} ${url}`);
    });
    globalThis.fetch = fetchMock as typeof fetch;

    renderAt('/orgs/acme/projects/9/settings/keys');
    const user = userEvent.setup();

    const row = await screen.findByTestId(`dsn-row-${KEY_A.id}`);
    await user.click(within(row).getByRole('button', { name: /Revoke key/i }));

    // Confirm modal — click the destructive confirm.
    await user.click(await screen.findByRole('button', { name: /Revoke key$/i }));

    await waitFor(() => {
      expect(screen.getByText(/No active keys yet/i)).toBeInTheDocument();
    });
    expect(revokeCalls).toBe(1);
  });

  it('toggles "show revoked" to refetch with includeRevoked=true and renders revoked rows read-only', async () => {
    const calls: { url: string; method: string }[] = [];
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === 'string' ? input : input.toString();
      const method = init?.method ?? 'GET';
      calls.push({ url, method });
      if (url.endsWith('/api/v1/projects/9/keys') && method === 'GET') {
        return jsonResponse([KEY_A]);
      }
      if (url.endsWith('/api/v1/projects/9/keys?includeRevoked=true') && method === 'GET') {
        return jsonResponse([KEY_A, { ...KEY_B, active: false }]);
      }
      throw new Error(`unexpected ${method} ${url}`);
    });
    globalThis.fetch = fetchMock as typeof fetch;

    renderAt('/orgs/acme/projects/9/settings/keys');
    const user = userEvent.setup();

    await screen.findByTestId(`dsn-row-${KEY_A.id}`);
    expect(screen.queryByTestId(`dsn-row-${KEY_B.id}`)).toBeNull();

    // Flip the toggle — second request goes to the includeRevoked variant.
    await user.click(screen.getByTestId('show-revoked-toggle'));

    await waitFor(() => {
      expect(screen.getByTestId(`dsn-row-${KEY_B.id}`)).toBeInTheDocument();
    });
    const includeRevokedCall = calls.find((c) =>
      c.url.endsWith('?includeRevoked=true'),
    );
    expect(includeRevokedCall).toBeDefined();

    // Revoked rows: status badge says "Revoked", rotate + revoke buttons are absent.
    const revokedRow = screen.getByTestId(`dsn-row-${KEY_B.id}`);
    expect(within(revokedRow).getByTestId(`dsn-status-${KEY_B.id}`)).toHaveTextContent(/Revoked/i);
    expect(within(revokedRow).queryByTestId(`dsn-rotate-${KEY_B.id}`)).toBeNull();
    expect(within(revokedRow).queryByTestId(`dsn-revoke-${KEY_B.id}`)).toBeNull();
  });

  it('rotate-confirm mints a fresh DSN and surfaces it in the reveal modal without touching the old key', async () => {
    const calls: { url: string; method: string }[] = [];
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === 'string' ? input : input.toString();
      const method = init?.method ?? 'GET';
      calls.push({ url, method });
      if (url.endsWith('/api/v1/projects/9/keys') && method === 'GET') {
        return jsonResponse([KEY_A]);
      }
      if (url.endsWith('/api/v1/projects/9/keys') && method === 'POST') {
        return jsonResponse(
          {
            ...KEY_B,
            dsn: 'arguslog://NEW@localhost:8080/api/9',
          },
          201,
        );
      }
      throw new Error(`unexpected ${method} ${url}`);
    });
    globalThis.fetch = fetchMock as typeof fetch;

    renderAt('/orgs/acme/projects/9/settings/keys');
    const user = userEvent.setup();

    const row = await screen.findByTestId(`dsn-row-${KEY_A.id}`);
    await user.click(within(row).getByTestId(`dsn-rotate-${KEY_A.id}`));

    // Rotate confirm modal — fires the POST.
    await user.click(await screen.findByRole('button', { name: /Mint replacement/i }));

    await waitFor(() => {
      expect(screen.getByText('arguslog://NEW@localhost:8080/api/9')).toBeInTheDocument();
    });
    // Critical: a rotate must NOT revoke the old key — operator does that manually after
    // running services pick up the new DSN.
    expect(calls.some((c) => c.method === 'DELETE')).toBe(false);
  });

  it('surfaces server problem details when revoke fails (already revoked race)', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === 'string' ? input : input.toString();
      const method = init?.method ?? 'GET';
      if (url.endsWith('/api/v1/projects/9/keys') && method === 'GET') {
        return jsonResponse([KEY_A]);
      }
      if (url.endsWith(`/api/v1/projects/9/keys/${KEY_A.id}`) && method === 'DELETE') {
        return problemResponse('DSN 101 is already revoked');
      }
      throw new Error(`unexpected ${method} ${url}`);
    });
    globalThis.fetch = fetchMock as typeof fetch;

    renderAt('/orgs/acme/projects/9/settings/keys');
    const user = userEvent.setup();

    const row = await screen.findByTestId(`dsn-row-${KEY_A.id}`);
    await user.click(within(row).getByRole('button', { name: /Revoke key/i }));
    await user.click(await screen.findByRole('button', { name: /Revoke key$/i }));

    await waitFor(() => {
      expect(screen.getByText('DSN 101 is already revoked')).toBeInTheDocument();
    });
  });
});
