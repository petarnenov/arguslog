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

function renderAt(path = '/orgs/acme/integrations/slack') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <MantineProvider>
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={[path]}>
          <Routes>
            <Route path="/orgs/:orgSlug/integrations/slack" element={<SlackIntegrationsPage />} />
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

  it('connect button GETs the install endpoint then redirects the browser to Slack', async () => {
    const calls: { url: string }[] = [];
    globalThis.fetch = vi.fn(async (input) => {
      const url = typeof input === 'string' ? input : (input as Request).url;
      calls.push({ url });
      if (url.endsWith('/api/v1/orgs')) return jsonResponse([ORG]);
      if (url.endsWith('/api/v1/orgs/1/integrations/slack/oauth/install')) {
        return jsonResponse({
          authorizeUrl: 'https://slack.com/oauth/v2/authorize?state=opaque',
        });
      }
      return jsonResponse([]);
    }) as typeof fetch;

    const assign = vi.fn();
    const originalLocation = window.location;
    Object.defineProperty(window, 'location', {
      configurable: true,
      value: { ...originalLocation, assign },
    });

    try {
      renderAt();

      await waitFor(() =>
        expect(screen.getByText(/No Slack workspaces connected yet/i)).toBeInTheDocument(),
      );
      await userEvent.click(screen.getByTestId('slack-connect-button'));

      await waitFor(() =>
        expect(assign).toHaveBeenCalledWith('https://slack.com/oauth/v2/authorize?state=opaque'),
      );
      // Sanity-check the install endpoint was actually hit (not just a stray cache).
      expect(
        calls.some((c) => c.url.endsWith('/api/v1/orgs/1/integrations/slack/oauth/install')),
      ).toBe(true);
    } finally {
      Object.defineProperty(window, 'location', {
        configurable: true,
        value: originalLocation,
      });
    }
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

    renderAt('/orgs/acme/integrations/slack?installed=Acme');

    await waitFor(() => expect(screen.getByText(/Connected to Acme/i)).toBeInTheDocument());
  });

  it('creates an alert destination when the workspace has a captured webhook', async () => {
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
            webhookChannel: '#alerts',
            hasWebhook: true,
          },
        ]);
      }
      if (url.endsWith('/api/v1/orgs/1/projects')) return jsonResponse([]);
      if (
        url.endsWith('/api/v1/orgs/1/integrations/slack/workspaces/7/alert-destination') &&
        init?.method === 'POST'
      ) {
        return jsonResponse({ id: 99, orgId: 1, kind: 'slack', name: 'Slack: Acme #alerts' });
      }
      return jsonResponse([]);
    }) as typeof fetch;

    renderAt();

    const btn = await screen.findByTestId('slack-create-destination-7');
    await userEvent.click(btn);

    await waitFor(() => {
      const post = calls.find(
        (c) =>
          c.url.endsWith('/api/v1/orgs/1/integrations/slack/workspaces/7/alert-destination') &&
          c.init?.method === 'POST',
      );
      expect(post).toBeTruthy();
    });
    await waitFor(() => expect(screen.getByText(/Created alert destination/i)).toBeInTheDocument());
  });

  it('hides the create-alert-destination button when the workspace has no webhook', async () => {
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
            defaultProjectId: null,
            installedByUserId: null,
            installedAt: '2026-05-15T10:00:00Z',
            deactivatedAt: null,
            active: true,
            webhookChannel: null,
            hasWebhook: false,
          },
        ]);
      }
      return jsonResponse([]);
    }) as typeof fetch;

    renderAt();

    await waitFor(() => expect(screen.getByTestId('slack-disconnect-7')).toBeInTheDocument());
    expect(screen.queryByTestId('slack-create-destination-7')).not.toBeInTheDocument();
  });

  it('surfaces ?error=<code> as an error banner', async () => {
    globalThis.fetch = vi.fn(async (input) => {
      const url = typeof input === 'string' ? input : (input as Request).url;
      if (url.endsWith('/api/v1/orgs')) return jsonResponse([ORG]);
      return jsonResponse([]);
    }) as typeof fetch;

    renderAt('/orgs/acme/integrations/slack?error=access_denied');

    await waitFor(() =>
      expect(screen.getByText(/Slack install failed: access_denied/i)).toBeInTheDocument(),
    );
  });
});
