import { MantineProvider } from '@mantine/core';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import '../../i18n';
import { ConnectProjectPage } from '../../pages/ConnectProjectPage';

const originalFetch = globalThis.fetch;

const ORG = { id: 1, slug: 'acme', name: 'Acme', plan: 'free', createdAt: '2026-05-01T00:00:00Z' };
const PROJECT = {
  id: 9,
  orgId: 1,
  slug: 'web',
  name: 'Web',
  platform: 'react',
  createdAt: '2026-05-01T00:00:00Z',
};

const MINTED_DSN = {
  id: 7,
  dsnPublic: 'pubkey123',
  dsn: 'arguslog://pubkey123:secret@ingest.example.com/api/9',
  createdAt: '2026-05-15T00:00:00Z',
  active: true,
};

const MINTED_PAT = {
  id: 42,
  name: 'Connect quickstart — Web',
  prefix: 'arglog_pat_abc',
  token: 'arglog_pat_abc_FULL_PLAINTEXT_KEY',
  createdAt: '2026-05-15T00:00:00Z',
  scopes: ['orgs:read'],
};

const EXISTING_QUICKSTART_PAT = {
  id: 41,
  name: 'Connect quickstart — Web',
  prefix: 'arglog_pat_old',
  createdAt: '2026-04-01T00:00:00Z',
  scopes: ['orgs:read'],
};

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function renderAt(path = '/orgs/acme/projects/9/connect') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <MantineProvider>
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={[path]}>
          <Routes>
            <Route
              path="/orgs/:orgSlug/projects/:projectId/connect"
              element={<ConnectProjectPage />}
            />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>
    </MantineProvider>,
  );
}

interface MockCall {
  url: string;
  method: string;
  body?: string;
}

function installFetchMock(
  opts: { existingTokens?: typeof EXISTING_QUICKSTART_PAT[] } = {},
): MockCall[] {
  const calls: MockCall[] = [];
  const existingTokens = opts.existingTokens ?? [];
  globalThis.fetch = vi.fn(async (input, init) => {
    const url = typeof input === 'string' ? input : (input as Request).url;
    const method = (init?.method ?? 'GET').toUpperCase();
    calls.push({
      url,
      method,
      body: typeof init?.body === 'string' ? init.body : undefined,
    });
    if (url.endsWith('/api/v1/orgs') && method === 'GET') return jsonResponse([ORG]);
    if (url.endsWith('/api/v1/orgs/1/projects') && method === 'GET') return jsonResponse([PROJECT]);
    if (url.endsWith('/api/v1/projects/9/keys') && method === 'GET') return jsonResponse([]);
    if (url.endsWith('/api/v1/projects/9/keys') && method === 'POST') return jsonResponse(MINTED_DSN, 201);
    if (url.endsWith('/api/v1/me/tokens') && method === 'GET') return jsonResponse(existingTokens);
    if (url.endsWith('/api/v1/me/tokens') && method === 'POST') return jsonResponse(MINTED_PAT, 201);
    return jsonResponse([]);
  }) as typeof fetch;
  return calls;
}

describe('ConnectProjectPage — AI agents tab', () => {
  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  it('renders the AI coding agents tab as the default, with all four sub-clients', async () => {
    installFetchMock();
    renderAt();
    await waitFor(() =>
      expect(screen.getByRole('heading', { name: /paste-ready snippets/i })).toBeInTheDocument(),
    );
    const agentGroupTab = screen.getByRole('tab', { name: /ai coding agents/i });
    expect(agentGroupTab).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByRole('tab', { name: /^claude code$/i })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: /^cursor$/i })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: /^codex$/i })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: /^github copilot$/i })).toBeInTheDocument();
    expect(screen.getByTestId('connect-snippet-copy-agent-claude-code')).toBeInTheDocument();
  });

  it('auto-provisions DSN + PAT on first visit (no rotate CTA, no missing-creds alert)', async () => {
    const calls = installFetchMock();
    renderAt();
    // Wait for both mutations to fire. Generous timeout because the effect needs every
    // query (orgs, projects, dsns, tokens) to settle before it triggers.
    await waitFor(
      () => {
        const posted = calls.filter((c) => c.method === 'POST');
        const dsnPost = posted.find((c) => c.url.endsWith('/api/v1/projects/9/keys'));
        const patPost = posted.find((c) => c.url.endsWith('/api/v1/me/tokens'));
        expect(dsnPost).toBeDefined();
        expect(patPost).toBeDefined();
      },
      { timeout: 3000 },
    );
    // Sent body for the PAT should NOT carry the rotation timestamp suffix yet.
    const patPost = calls.find(
      (c) => c.method === 'POST' && c.url.endsWith('/api/v1/me/tokens'),
    );
    expect(patPost!.body).toContain('"name":"Connect quickstart — Web"');
    expect(patPost!.body).not.toMatch(/Connect quickstart — Web — \d{4}-\d{2}-\d{2}/);
    // Rotate CTA is hidden (this is the first visit).
    expect(screen.queryByTestId('connect-rotate-cta')).not.toBeInTheDocument();
  });

  it('does NOT auto-provision when a Connect quickstart PAT already exists (return visit)', async () => {
    const calls = installFetchMock({ existingTokens: [EXISTING_QUICKSTART_PAT] });
    renderAt();
    await waitFor(() =>
      expect(screen.getByRole('heading', { name: /paste-ready snippets/i })).toBeInTheDocument(),
    );
    // Give the page a tick to settle, then assert no POSTs happened.
    await new Promise((r) => setTimeout(r, 50));
    const posted = calls.filter((c) => c.method === 'POST');
    expect(posted).toHaveLength(0);
    // Rotate CTA is now visible.
    expect(screen.getByTestId('connect-rotate-cta')).toBeInTheDocument();
  });

  it('Rotate button mints a fresh PAT with a date-suffixed name', async () => {
    const calls = installFetchMock({ existingTokens: [EXISTING_QUICKSTART_PAT] });
    renderAt();
    await waitFor(() => expect(screen.getByTestId('connect-rotate-cta')).toBeInTheDocument());
    const user = userEvent.setup();
    await user.click(screen.getByTestId('connect-rotate-pat'));
    await waitFor(() => {
      const patPost = calls.find(
        (c) => c.method === 'POST' && c.url.endsWith('/api/v1/me/tokens'),
      );
      expect(patPost).toBeDefined();
      expect(patPost!.body).toMatch(/Connect quickstart — Web — \d{4}-\d{2}-\d{2}/);
    });
  });
});
