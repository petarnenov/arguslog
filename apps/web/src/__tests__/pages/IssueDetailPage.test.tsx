import { MantineProvider } from '@mantine/core';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import '../../i18n';
import { IssueDetailPage } from '../../pages/IssueDetailPage';

const originalFetch = globalThis.fetch;

const sampleIssue = {
  id: 7,
  projectId: 101,
  fingerprint: 'fp-x',
  status: 'unresolved',
  level: 'error',
  title: 'TypeError: x is undefined',
  culprit: 'render at app.js:42',
  firstSeenAt: '2026-05-05T10:00:00Z',
  lastSeenAt: '2026-05-05T11:00:00Z',
  occurrenceCount: 12,
};

const sampleEventsPage = {
  data: [
    {
      id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
      issueId: 7,
      projectId: 101,
      receivedAt: '2026-05-05T11:00:00Z',
      payload: { level: 'error', message: 'boom', exception: { values: [{ type: 'TypeError' }] } },
    },
  ],
  page: {},
};

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function renderAt(path: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <MantineProvider>
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={[path]}>
          <Routes>
            <Route
              path="/orgs/:orgSlug/projects/:projectId/issues/:issueId"
              element={<IssueDetailPage />}
            />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>
    </MantineProvider>,
  );
}

describe('IssueDetailPage', () => {
  beforeEach(() => {
    globalThis.fetch = originalFetch;
  });
  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  it('renders the issue header + meta + recent events', async () => {
    globalThis.fetch = vi.fn(async (input) => {
      const url = typeof input === 'string' ? input : (input as Request).url;
      if (url.includes('/events')) return jsonResponse(sampleEventsPage);
      return jsonResponse(sampleIssue);
    }) as typeof fetch;

    renderAt('/orgs/acme/projects/101/issues/7');

    await waitFor(() => expect(screen.getByText('TypeError: x is undefined')).toBeInTheDocument());
    expect(screen.getByText('render at app.js:42')).toBeInTheDocument();
    // Both the badge and a heading-level "Error" label can match the regex
    expect(screen.getAllByText(/error/i).length).toBeGreaterThan(0);
    await waitFor(() => expect(screen.getByTestId('events-table')).toBeInTheDocument());
    expect(screen.getByText(/aaaaaaaa/i)).toBeInTheDocument();
  });

  it('shows an empty-events state when the api returns no events', async () => {
    globalThis.fetch = vi.fn(async (input) => {
      const url = typeof input === 'string' ? input : (input as Request).url;
      if (url.includes('/events')) return jsonResponse({ data: [], page: {} });
      return jsonResponse(sampleIssue);
    }) as typeof fetch;

    renderAt('/orgs/acme/projects/101/issues/7');

    await waitFor(() => expect(screen.getByText(/No events recorded/i)).toBeInTheDocument());
  });

  it('refuses to call the api when the URL has a non-numeric issue id', async () => {
    const spy = vi.fn(async () => jsonResponse(sampleIssue));
    globalThis.fetch = spy as typeof fetch;

    renderAt('/orgs/acme/projects/101/issues/oops');

    await waitFor(() => expect(screen.getByText(/Invalid project/i)).toBeInTheDocument());
    expect(spy).not.toHaveBeenCalled();
  });
});
