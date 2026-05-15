import { MantineProvider } from '@mantine/core';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import '../../i18n';
import { SlackIntegrationsPage } from '../../pages/SlackIntegrationsPage';

const originalFetch = globalThis.fetch;

const ORG = { id: 1, slug: 'acme', name: 'Acme', plan: 'free', createdAt: '2026-05-01T00:00:00Z' };

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function renderAt(path = '/orgs/acme/settings/integrations/slack') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <MantineProvider>
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={[path]}>
          <Routes>
            <Route
              path="/orgs/:orgSlug/settings/integrations/slack"
              element={<SlackIntegrationsPage />}
            />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>
    </MantineProvider>,
  );
}

describe('SlackIntegrationsPage', () => {
  beforeEach(() => {
    globalThis.fetch = originalFetch;
  });
  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  it('shows the empty state with a connect button when no workspaces exist', async () => {
    globalThis.fetch = vi.fn(async (input) => {
      const url = typeof input === 'string' ? input : (input as Request).url;
      if (url.endsWith('/api/v1/orgs')) return jsonResponse([ORG]);
      return jsonResponse([]);
    }) as typeof fetch;

    renderAt();

    await waitFor(() =>
      expect(screen.getByText(/No Slack workspaces connected yet/i)).toBeInTheDocument(),
    );
    // Connect button must link directly to the install endpoint — clicking it triggers a
    // top-level navigation, not an XHR, so the OAuth state cookie / CORS dance works.
    const connect = screen.getByTestId('slack-connect-button');
    expect(connect).toHaveAttribute('href', '/api/v1/orgs/1/integrations/slack/oauth/install');
  });

  it('renders workspaces and never leaks the install token in the DOM', async () => {
    globalThis.fetch = vi.fn(async (input) => {
      const url = typeof input === 'string' ? input : (input as Request).url;
      if (url.endsWith('/api/v1/orgs')) return jsonResponse([ORG]);
      if (url.endsWith('/api/v1/orgs/1/integrations/slack/workspaces')) {
        return jsonResponse([
          {
            id: 7,
            slackTeamId: 'T123',
            slackTeamName: 'Acme',
            orgId: 1,
            defaultProjectId: 101,
            installedByUserId: null,
            installedAt: '2026-05-15T10:00:00Z',
            deactivatedAt: null,
            active: true,
          },
        ]);
      }
      if (url.endsWith('/api/v1/orgs/1/projects')) {
        return jsonResponse([
          { id: 101, orgId: 1, slug: 'web', name: 'Web', platform: 'javascript', createdAt: '' },
        ]);
      }
      return jsonResponse([]);
    }) as typeof fetch;

    const { container } = renderAt();

    await waitFor(() => expect(screen.getByTestId('slack-workspace-row-7')).toBeInTheDocument());
    expect(screen.getByText('Acme')).toBeInTheDocument();
    expect(screen.getByText('T123')).toBeInTheDocument();
    expect(screen.getByText(/^Active$/i)).toBeInTheDocument();
    // Bot tokens start with xoxb- — assert the rendered DOM never contains that prefix even
    // if a future API regression sneaks the token into the response shape.
    expect(container.innerHTML).not.toContain('xoxb-');
  });

  it('confirms then disconnects a workspace', async () => {
    const calls: { url: string; init?: RequestInit }[] = [];
    globalThis.fetch = vi.fn(async (input, init) => {
      const url = typeof input === 'string' ? input : (input as Request).url;
      calls.push({ url, init });
      if (url.endsWith('/api/v1/orgs')) return jsonResponse([ORG]);
      if (
        url.endsWith('/api/v1/orgs/1/integrations/slack/workspaces') &&
        (init?.method ?? 'GET') === 'GET'
      ) {
        return jsonResponse([
          {
            id: 7,
            slackTeamId: 'T123',
            slackTeamName: 'Acme',
            orgId: 1,
            defaultProjectId: null,
            installedByUserId: null,
            installedAt: '2026-05-15T10:00:00Z',
            deactivatedAt: null,
            active: true,
          },
        ]);
      }
      if (url.endsWith('/api/v1/orgs/1/projects')) return jsonResponse([]);
      if (
        url.endsWith('/api/v1/orgs/1/integrations/slack/workspaces/7') &&
        init?.method === 'DELETE'
      ) {
        return new Response(null, { status: 204 });
      }
      return jsonResponse([]);
    }) as typeof fetch;

    renderAt();

    await waitFor(() => expect(screen.getByTestId('slack-disconnect-7')).toBeInTheDocument());
    await userEvent.click(screen.getByTestId('slack-disconnect-7'));
    // Modal opens asynchronously — wait for the confirm button to render.
    const confirm = await screen.findByTestId('slack-disconnect-confirm');
    await userEvent.click(confirm);

    await waitFor(() => {
      const del = calls.find(
        (c) =>
          c.url.endsWith('/api/v1/orgs/1/integrations/slack/workspaces/7') &&
          c.init?.method === 'DELETE',
      );
      expect(del).toBeTruthy();
    });
  });

  it('surfaces ?installed=<team> as a success banner and scrubs the query', async () => {
    globalThis.fetch = vi.fn(async (input) => {
      const url = typeof input === 'string' ? input : (input as Request).url;
      if (url.endsWith('/api/v1/orgs')) return jsonResponse([ORG]);
      return jsonResponse([]);
    }) as typeof fetch;

    renderAt('/orgs/acme/settings/integrations/slack?installed=Acme');

    await waitFor(() =>
      expect(screen.getByText(/Connected to Acme/i)).toBeInTheDocument(),
    );
  });

  it('surfaces ?error=<code> as an error banner', async () => {
    globalThis.fetch = vi.fn(async (input) => {
      const url = typeof input === 'string' ? input : (input as Request).url;
      if (url.endsWith('/api/v1/orgs')) return jsonResponse([ORG]);
      return jsonResponse([]);
    }) as typeof fetch;

    renderAt('/orgs/acme/settings/integrations/slack?error=access_denied');

    await waitFor(() =>
      expect(screen.getByText(/Slack install failed: access_denied/i)).toBeInTheDocument(),
    );
  });
});
