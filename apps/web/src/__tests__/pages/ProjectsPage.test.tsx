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

  it('chains createProject then createDsn and shows the DSN modal on success', async () => {
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
        return jsonResponse(NEW_PROJECT);
      }
      if (url.endsWith('/api/v1/projects/9/keys') && method === 'POST') {
        return jsonResponse(NEW_DSN);
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

    // Both endpoints were hit.
    const calls = fetchMock.mock.calls.map(([input, init]) => {
      const url = typeof input === 'string' ? input : (input as URL | Request).toString();
      const method = (init as RequestInit | undefined)?.method ?? 'GET';
      return `${method} ${url}`;
    });
    expect(calls.some((c) => c.startsWith('POST ') && c.endsWith('/api/v1/orgs/1/projects'))).toBe(
      true,
    );
    expect(calls.some((c) => c.startsWith('POST ') && c.endsWith('/api/v1/projects/9/keys'))).toBe(
      true,
    );
  });

  it('shows a retry path when project is created but DSN issuance fails', async () => {
    let keysAttempts = 0;
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
        return jsonResponse(NEW_PROJECT);
      }
      if (url.endsWith('/api/v1/projects/9/keys') && method === 'POST') {
        keysAttempts += 1;
        // First attempt fails; retry succeeds.
        return keysAttempts === 1
          ? problemResponse('temporary keystore outage')
          : jsonResponse(NEW_DSN);
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

    // Failure surfaced in the success modal, with a retry control.
    await waitFor(() => {
      expect(screen.getByText('temporary keystore outage')).toBeInTheDocument();
    });
    const retryBtn = screen.getByRole('button', { name: /Retry generating DSN/i });
    await user.click(retryBtn);

    await waitFor(() => {
      expect(screen.getByText('arguslog://PUB@localhost:8080/api/9')).toBeInTheDocument();
    });
    expect(keysAttempts).toBe(2);
  });
});
