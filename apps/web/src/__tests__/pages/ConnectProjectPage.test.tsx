import { MantineProvider } from '@mantine/core';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
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

describe('ConnectProjectPage — AI agents tab', () => {
  beforeEach(() => {
    // GET /api/v1/orgs → org list; GET /api/v1/orgs/1/projects → project list;
    // GET /api/v1/projects/9/dsns → empty so the page reaches the snippet section.
    globalThis.fetch = vi.fn(async (input) => {
      const url = typeof input === 'string' ? input : (input as Request).url;
      if (url.endsWith('/api/v1/orgs')) return jsonResponse([ORG]);
      if (url.endsWith('/api/v1/orgs/1/projects')) return jsonResponse([PROJECT]);
      if (url.endsWith('/api/v1/projects/9/dsns')) return jsonResponse([]);
      return jsonResponse([]);
    }) as typeof fetch;
  });
  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  it('renders the AI coding agents tab as the default, with all four sub-clients', async () => {
    renderAt();
    // Page has loaded once the snippets section title is visible.
    await waitFor(() =>
      expect(screen.getByRole('heading', { name: /paste-ready snippets/i })).toBeInTheDocument(),
    );

    // The "AI coding agents" group tab exists and is selected by default.
    const agentGroupTab = screen.getByRole('tab', { name: /ai coding agents/i });
    expect(agentGroupTab).toBeInTheDocument();
    expect(agentGroupTab).toHaveAttribute('aria-selected', 'true');

    // All four sub-clients render as inner tabs within the agent panel.
    expect(screen.getByRole('tab', { name: /^claude code$/i })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: /^cursor$/i })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: /^codex$/i })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: /^github copilot$/i })).toBeInTheDocument();

    // Copy button for the default-selected (claude-code) prompt is reachable.
    expect(
      screen.getByTestId('connect-snippet-copy-agent-claude-code'),
    ).toBeInTheDocument();
  });

  it('shows the missing-credentials hint when neither DSN nor PAT is minted yet', async () => {
    renderAt();
    await waitFor(() =>
      expect(screen.getByRole('heading', { name: /paste-ready snippets/i })).toBeInTheDocument(),
    );
    // The Alert appears inside the default-selected agent panel.
    expect(screen.getByText(/will fill them in automatically|inlined into the prompts/i))
      .toBeInTheDocument();
  });
});
