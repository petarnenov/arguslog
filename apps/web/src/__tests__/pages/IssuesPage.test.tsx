import { MantineProvider } from '@mantine/core';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import '../../i18n';
import { IssuesPage } from '../../pages/IssuesPage';

const originalFetch = globalThis.fetch;

function renderAt(path: string) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <MantineProvider>
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={[path]}>
          <Routes>
            <Route path="/orgs/:orgSlug/projects/:projectId/issues" element={<IssuesPage />} />
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

describe('IssuesPage', () => {
  beforeEach(() => {
    globalThis.fetch = originalFetch;
  });
  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  it('renders the issues table when the api returns rows', async () => {
    globalThis.fetch = vi.fn(async () =>
      jsonResponse({
        data: [
          {
            id: 7,
            projectId: 101,
            fingerprint: 'fp',
            status: 'unresolved',
            level: 'error',
            title: 'TypeError: x',
            culprit: 'render at app.js:42',
            firstSeenAt: '2026-05-05T10:00:00Z',
            lastSeenAt: '2026-05-05T11:00:00Z',
            occurrenceCount: 3,
          },
        ],
        page: {},
      }),
    ) as typeof fetch;

    renderAt('/orgs/acme/projects/101/issues');

    await waitFor(() => expect(screen.getByTestId('issues-table')).toBeInTheDocument());
    expect(screen.getByText('TypeError: x')).toBeInTheDocument();
    expect(screen.getByText('render at app.js:42')).toBeInTheDocument();
  });

  it('shows the empty state when the api returns no rows', async () => {
    globalThis.fetch = vi.fn(async () => jsonResponse({ data: [], page: {} })) as typeof fetch;

    renderAt('/orgs/acme/projects/101/issues');

    await waitFor(() => expect(screen.getByText(/your code is healthy/i)).toBeInTheDocument());
  });

  it('refuses to call the api with a non-numeric project id', async () => {
    const spy = vi.fn(async () => jsonResponse({ data: [], page: {} }));
    globalThis.fetch = spy as typeof fetch;

    renderAt('/orgs/acme/projects/not-a-number/issues');

    await waitFor(() => expect(screen.getByText(/Invalid project/i)).toBeInTheDocument());
    expect(spy).not.toHaveBeenCalled();
  });

  it('sets ?status= when the user picks a status filter', async () => {
    const calls: string[] = [];
    globalThis.fetch = vi.fn(async (input) => {
      calls.push(typeof input === 'string' ? input : (input as Request).url);
      return jsonResponse({ data: [], page: {} });
    }) as typeof fetch;

    renderAt('/orgs/acme/projects/101/issues');

    await waitFor(() => expect(calls.length).toBeGreaterThan(0));
    const [statusSelect] = screen.getAllByPlaceholderText('Status');
    await userEvent.click(statusSelect);
    await userEvent.click(await screen.findByRole('option', { name: 'Resolved' }));

    await waitFor(() => expect(calls.some((u) => u.includes('status=resolved'))).toBe(true));
  });
});
