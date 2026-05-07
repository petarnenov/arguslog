import { MantineProvider } from '@mantine/core';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import '../../i18n';
import { PersonalAccessTokensPage } from '../../pages/PersonalAccessTokensPage';

const originalFetch = globalThis.fetch;

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function renderAt(path = '/me/tokens') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <MantineProvider>
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={[path]}>
          <Routes>
            <Route path="/me/tokens" element={<PersonalAccessTokensPage />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>
    </MantineProvider>,
  );
}

describe('PersonalAccessTokensPage', () => {
  beforeEach(() => {
    globalThis.fetch = originalFetch;
  });
  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  it('lists tokens the api returns', async () => {
    globalThis.fetch = vi.fn(async () =>
      jsonResponse([
        {
          id: 1,
          name: 'ci-deploy',
          prefix: 'arglog_p',
          createdAt: '2026-05-01T10:00:00Z',
          lastUsedAt: '2026-05-05T11:00:00Z',
        },
        {
          id: 2,
          name: 'local-dev',
          prefix: 'arglog_q',
          createdAt: '2026-05-02T10:00:00Z',
        },
      ]),
    ) as typeof fetch;

    renderAt();

    await waitFor(() => expect(screen.getByTestId('tokens-table')).toBeInTheDocument());
    expect(screen.getByText('ci-deploy')).toBeInTheDocument();
    expect(screen.getByText('local-dev')).toBeInTheDocument();
    expect(screen.getByText(/never/i)).toBeInTheDocument();
  });

  it('shows the empty state when no tokens exist', async () => {
    globalThis.fetch = vi.fn(async () => jsonResponse([])) as typeof fetch;

    renderAt();

    await waitFor(() =>
      expect(screen.getByText(/no tokens yet — create one above/i)).toBeInTheDocument(),
    );
  });

  it('mints a new token and reveals the plaintext exactly once', async () => {
    let listCalls = 0;
    globalThis.fetch = vi.fn(async (input, init) => {
      const url = typeof input === 'string' ? input : (input as Request).url;
      if (url.endsWith('/api/v1/me/tokens') && (!init || init.method === undefined)) {
        listCalls += 1;
        if (listCalls === 1) return jsonResponse([]);
        return jsonResponse([
          {
            id: 7,
            name: 'ci-deploy',
            prefix: 'arglog_p',
            createdAt: '2026-05-06T12:00:00Z',
          },
        ]);
      }
      if (url.endsWith('/api/v1/me/tokens') && init?.method === 'POST') {
        return jsonResponse(
          {
            id: 7,
            name: 'ci-deploy',
            prefix: 'arglog_p',
            token: 'arglog_pat_abcdef12_secretSecretSecret',
            createdAt: '2026-05-06T12:00:00Z',
          },
          201,
        );
      }
      throw new Error(`unexpected fetch: ${url}`);
    }) as typeof fetch;

    renderAt();

    await screen.findByText(/no tokens yet/i);
    await userEvent.type(screen.getByTestId('pat-name-input'), 'ci-deploy');
    await userEvent.click(screen.getByTestId('pat-create-button'));

    await waitFor(() =>
      expect(screen.getByTestId('pat-plaintext')).toHaveTextContent(
        'arglog_pat_abcdef12_secretSecretSecret',
      ),
    );
    await waitFor(() => expect(screen.getByText('ci-deploy')).toBeInTheDocument());
  });

  it('mints a token with explicit scopes when the all-scopes toggle is off', async () => {
    let postBody: unknown;
    let listCalls = 0;
    globalThis.fetch = vi.fn(async (input, init) => {
      const url = typeof input === 'string' ? input : (input as Request).url;
      if (url.endsWith('/api/v1/me/tokens') && (!init || init.method === undefined)) {
        listCalls += 1;
        if (listCalls === 1) return jsonResponse([]);
        return jsonResponse([
          {
            id: 11,
            name: 'release-bot',
            prefix: 'arglog_r',
            createdAt: '2026-05-06T12:00:00Z',
            scopes: ['releases:write', 'sourcemaps:write'],
          },
        ]);
      }
      if (url.endsWith('/api/v1/me/tokens') && init?.method === 'POST') {
        postBody = JSON.parse(init.body as string);
        return jsonResponse(
          {
            id: 11,
            name: 'release-bot',
            prefix: 'arglog_r',
            token: 'arglog_pat_abcdef12_secretSecretSecret',
            createdAt: '2026-05-06T12:00:00Z',
            scopes: ['releases:write', 'sourcemaps:write'],
          },
          201,
        );
      }
      throw new Error(`unexpected fetch: ${url}`);
    }) as typeof fetch;

    renderAt();

    await screen.findByText(/no tokens yet/i);
    await userEvent.type(screen.getByTestId('pat-name-input'), 'release-bot');
    await userEvent.click(screen.getByTestId('pat-scope-all-toggle'));
    await userEvent.click(screen.getByTestId('pat-scope-releases:write'));
    await userEvent.click(screen.getByTestId('pat-scope-sourcemaps:write'));
    await userEvent.click(screen.getByTestId('pat-create-button'));

    await waitFor(() =>
      expect(screen.getByTestId('pat-plaintext')).toHaveTextContent(
        'arglog_pat_abcdef12_secretSecretSecret',
      ),
    );
    expect(postBody).toMatchObject({
      name: 'release-bot',
      scopes: ['releases:write', 'sourcemaps:write'],
    });
  });

  it('revokes a token after confirmation', async () => {
    let listCalls = 0;
    const deleteCalls: string[] = [];
    globalThis.fetch = vi.fn(async (input, init) => {
      const url = typeof input === 'string' ? input : (input as Request).url;
      if (url.endsWith('/api/v1/me/tokens') && (!init || init.method === undefined)) {
        listCalls += 1;
        if (listCalls === 1) {
          return jsonResponse([
            {
              id: 9,
              name: 'old-token',
              prefix: 'arglog_z',
              createdAt: '2026-05-01T00:00:00Z',
            },
          ]);
        }
        return jsonResponse([]);
      }
      if (init?.method === 'DELETE' && url.endsWith('/api/v1/me/tokens/9')) {
        deleteCalls.push(url);
        return new Response(null, { status: 204 });
      }
      throw new Error(`unexpected fetch: ${url}`);
    }) as typeof fetch;

    renderAt();

    await screen.findByText('old-token');
    await userEvent.click(screen.getByLabelText(/revoke old-token/i));
    const confirm = await screen.findByTestId('pat-revoke-confirm');
    await userEvent.click(confirm);

    await waitFor(() => expect(deleteCalls).toHaveLength(1));
    await waitFor(() => expect(screen.queryByText('old-token')).not.toBeInTheDocument());
  });
});
