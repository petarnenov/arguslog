import { MantineProvider } from '@mantine/core';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
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

  it('renders the symbolicated stack and toggles back to raw on demand', async () => {
    const symbolicatedEvents = {
      data: [
        {
          id: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
          issueId: 7,
          projectId: 101,
          receivedAt: '2026-05-05T11:00:00Z',
          payload: {
            release: '1.2.3',
            exception: {
              values: [
                {
                  type: 'TypeError',
                  stacktrace: {
                    frames: [
                      {
                        filename: 'dist/app.abc.js',
                        function: 'r',
                        lineno: 1,
                        colno: 42,
                        originalFilename: 'src/app.ts',
                        originalFunction: 'render',
                        originalLineno: 10,
                        originalColno: 4,
                      },
                    ],
                  },
                },
              ],
            },
          },
        },
      ],
      page: {},
    };
    globalThis.fetch = vi.fn(async (input) => {
      const url = typeof input === 'string' ? input : (input as Request).url;
      if (url.includes('/events')) return jsonResponse(symbolicatedEvents);
      return jsonResponse(sampleIssue);
    }) as typeof fetch;

    renderAt('/orgs/acme/projects/101/issues/7');

    // Default view: original location is shown.
    await waitFor(() => expect(screen.getByText('src/app.ts:10:4')).toBeInTheDocument());
    expect(screen.getByText('render')).toBeInTheDocument();

    // Toggle to "Minified" — raw fields surface, original badge disappears.
    await userEvent.click(screen.getByRole('radio', { name: 'Minified' }));
    await waitFor(() => expect(screen.getByText('dist/app.abc.js:1:42')).toBeInTheDocument());
    expect(screen.queryByText('src/app.ts:10:4')).not.toBeInTheDocument();
  });

  it('hides the toggle when no event has any symbolicated frame', async () => {
    globalThis.fetch = vi.fn(async (input) => {
      const url = typeof input === 'string' ? input : (input as Request).url;
      if (url.includes('/events')) return jsonResponse(sampleEventsPage);
      return jsonResponse(sampleIssue);
    }) as typeof fetch;

    renderAt('/orgs/acme/projects/101/issues/7');

    await waitFor(() => expect(screen.getByTestId('events-table')).toBeInTheDocument());
    expect(screen.queryByRole('radio', { name: 'Minified' })).not.toBeInTheDocument();
    expect(screen.queryByRole('radio', { name: 'Original' })).not.toBeInTheDocument();
  });
});
