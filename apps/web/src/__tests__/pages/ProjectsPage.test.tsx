import { MantineProvider } from '@mantine/core';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import '../../i18n';
import { ProjectsPage } from '../../pages/ProjectsPage';

const originalFetch = globalThis.fetch;

function renderAt(path: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <MantineProvider>
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={[path]}>
          <Routes>
            <Route path="/orgs/:orgSlug/projects" element={<ProjectsPage />} />
            <Route
              path="/orgs/:orgSlug/projects/:projectId/issues"
              element={<div data-testid="issues-page" />}
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

function problemResponse(detail: string, status = 500) {
  return new Response(JSON.stringify({ title: 'Server error', detail }), {
    status,
    headers: { 'Content-Type': 'application/problem+json' },
  });
}

const ORG = { id: 1, slug: 'acme', name: 'Acme', plan: 'free', createdAt: 't' };
const NEW_PROJECT = {
  id: 9,
  orgId: 1,
  slug: 'web',
  name: 'Web',
  platform: 'javascript',
  createdAt: 't',
};
const NEW_DSN = {
  id: 100,
  projectId: 9,
  dsnPublic: 'PUB',
  dsn: 'arguslog://PUB@localhost:8080/api/9',
  active: true,
  createdAt: 't',
};

describe('ProjectsPage', () => {
  beforeEach(() => {
    globalThis.fetch = originalFetch;
  });
  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  it('creates project + DSN atomically and shows the DSN modal on success', async () => {
    let projectsListed = 0;
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === 'string' ? input : input.toString();
      const method = init?.method ?? 'GET';
      if (url.endsWith('/api/v1/orgs') && method === 'GET') {
        return jsonResponse([ORG]);
      }
      if (url.endsWith('/api/v1/orgs/1/projects') && method === 'GET') {
        // First call: empty list. After project create + invalidate, returns the new project.
        projectsListed += 1;
        return jsonResponse(projectsListed === 1 ? [] : [NEW_PROJECT]);
      }
      if (url.endsWith('/api/v1/orgs/1/projects') && method === 'POST') {
        // Server now mints the first DSN inline (GH #26).
        return jsonResponse({ project: NEW_PROJECT, dsn: NEW_DSN });
      }
      throw new Error(`unexpected ${method} ${url}`);
    });
    globalThis.fetch = fetchMock as typeof fetch;

    renderAt('/orgs/acme/projects');
    const user = userEvent.setup();

    // Wait for the org-scoped page to render the "New project" CTA.
    const newProjectBtn = await screen.findByRole('button', { name: /New project/i });
    await user.click(newProjectBtn);

    await user.type(await screen.findByLabelText(/Project name/i), 'Web');
    // Submit the create form (button inside modal, same label as the toolbar button).
    const submitBtns = screen.getAllByRole('button', { name: /New project/i });
    const submitBtn = submitBtns.at(-1);
    if (!submitBtn) throw new Error('submit button not found');
    await user.click(submitBtn);

    await waitFor(() => {
      expect(screen.getByText('arguslog://PUB@localhost:8080/api/9')).toBeInTheDocument();
    });

    // Continue navigates to the new project's issues page.
    await user.click(screen.getByRole('button', { name: /Open project/i }));
    await waitFor(() => {
      expect(screen.getByTestId('issues-page')).toBeInTheDocument();
    });

    // Only one POST — the chained createDsn is gone.
    const calls = fetchMock.mock.calls.map(([input, init]) => {
      const url = typeof input === 'string' ? input : (input as URL | Request).toString();
      const method = (init as RequestInit | undefined)?.method ?? 'GET';
      return `${method} ${url}`;
    });
    expect(calls.some((c) => c.startsWith('POST ') && c.endsWith('/api/v1/orgs/1/projects'))).toBe(
      true,
    );
    expect(calls.some((c) => c.startsWith('POST ') && c.endsWith('/api/v1/projects/9/keys'))).toBe(
      false,
    );
  });

  it('surfaces create errors in the form without opening the success modal', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === 'string' ? input : input.toString();
      const method = init?.method ?? 'GET';
      if (url.endsWith('/api/v1/orgs') && method === 'GET') {
        return jsonResponse([ORG]);
      }
      if (url.endsWith('/api/v1/orgs/1/projects') && method === 'GET') {
        return jsonResponse([]);
      }
      if (url.endsWith('/api/v1/orgs/1/projects') && method === 'POST') {
        return problemResponse('temporary keystore outage');
      }
      throw new Error(`unexpected ${method} ${url}`);
    });
    globalThis.fetch = fetchMock as typeof fetch;

    renderAt('/orgs/acme/projects');
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: /New project/i }));
    await user.type(await screen.findByLabelText(/Project name/i), 'Web');
    const submitBtns = screen.getAllByRole('button', { name: /New project/i });
    const submitBtn = submitBtns.at(-1);
    if (!submitBtn) throw new Error('submit button not found');
    await user.click(submitBtn);

    // Failure surfaced in the create form (no half-created project — atomic backend).
    await waitFor(() => {
      expect(screen.getByText('temporary keystore outage')).toBeInTheDocument();
    });
    // DSN modal should not appear.
    expect(screen.queryByText(/arguslog:\/\/PUB/)).not.toBeInTheDocument();
  });

  it('renders stats numbers + sparkline when the api returns project.stats', async () => {
    const ACTIVE_PROJECT = {
      ...NEW_PROJECT,
      stats: {
        unresolvedIssueCount: 7,
        events24h: 142,
        events7d: 1024,
        lastEventAt: new Date(Date.now() - 60_000).toISOString(),
        eventsByDay: Array.from({ length: 14 }, (_, i) => ({
          day: new Date(Date.now() - (13 - i) * 86_400_000).toISOString().slice(0, 10),
          count: i * 5,
        })),
      },
    };
    globalThis.fetch = vi.fn(async (input: RequestInfo | URL) => {
      const url = typeof input === 'string' ? input : input.toString();
      if (url.endsWith('/api/v1/orgs')) return jsonResponse([ORG]);
      if (url.endsWith('/api/v1/orgs/1/projects')) return jsonResponse([ACTIVE_PROJECT]);
      throw new Error(`unexpected ${url}`);
    }) as typeof fetch;

    renderAt('/orgs/acme/projects');

    await screen.findByTestId('project-card-web');
    const stats = await screen.findByTestId('project-stats-web');
    // Numbers are formatted with thousands separator — match relaxed.
    expect(stats.textContent).toContain('7');
    expect(stats.textContent).toContain('142');
    expect(stats.textContent).toMatch(/1[,. ]?024/); // locale-tolerant
    // Sparkline container renders.
    expect(screen.getByTestId('project-sparkline-web')).toBeInTheDocument();
  });

  it('shows "No events yet" when the project has stats but lastEventAt is null', async () => {
    const QUIET_PROJECT = {
      ...NEW_PROJECT,
      stats: {
        unresolvedIssueCount: 0,
        events24h: 0,
        events7d: 0,
        lastEventAt: null,
        eventsByDay: Array.from({ length: 14 }, (_, i) => ({
          day: new Date(Date.now() - (13 - i) * 86_400_000).toISOString().slice(0, 10),
          count: 0,
        })),
      },
    };
    globalThis.fetch = vi.fn(async (input: RequestInfo | URL) => {
      const url = typeof input === 'string' ? input : input.toString();
      if (url.endsWith('/api/v1/orgs')) return jsonResponse([ORG]);
      if (url.endsWith('/api/v1/orgs/1/projects')) return jsonResponse([QUIET_PROJECT]);
      throw new Error(`unexpected ${url}`);
    }) as typeof fetch;

    renderAt('/orgs/acme/projects');

    await screen.findByTestId('project-no-events-web');
    expect(screen.getByText(/No events yet/i)).toBeInTheDocument();
    expect(screen.queryByTestId('project-sparkline-web')).not.toBeInTheDocument();
  });
});
