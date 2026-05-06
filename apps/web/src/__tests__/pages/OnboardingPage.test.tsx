import { MantineProvider } from '@mantine/core';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import '../../i18n';
import { OnboardingPage } from '../../pages/OnboardingPage';

const originalFetch = globalThis.fetch;

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <MantineProvider>
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={['/onboarding']}>
          <OnboardingPage />
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

describe('OnboardingPage', () => {
  beforeEach(() => {
    globalThis.fetch = originalFetch;
  });
  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  it('runs org → project → key in sequence and shows the DSN modal on success', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = typeof input === 'string' ? input : input.toString();
      if (url.endsWith('/api/v1/orgs')) {
        return jsonResponse({ id: 1, slug: 'acme', name: 'Acme', plan: 'free', createdAt: 't' });
      }
      if (url.endsWith('/api/v1/orgs/1/projects')) {
        return jsonResponse({
          id: 9,
          orgId: 1,
          slug: 'web',
          name: 'Web',
          platform: 'javascript',
          createdAt: 't',
        });
      }
      if (url.endsWith('/api/v1/projects/9/keys')) {
        return jsonResponse({
          id: 100,
          projectId: 9,
          dsnPublic: 'PUB',
          dsn: 'arguslog://PUB@localhost:8080/api/9',
          active: true,
          createdAt: 't',
        });
      }
      throw new Error('unexpected ' + url);
    });
    globalThis.fetch = fetchMock as typeof fetch;

    renderPage();
    const user = userEvent.setup();

    await user.type(screen.getByLabelText(/Organization name/i), 'Acme');
    await user.type(screen.getByLabelText(/Project name/i), 'Web');
    await user.click(screen.getByRole('button', { name: /Create project/i }));

    await waitFor(() => {
      expect(screen.getByText('arguslog://PUB@localhost:8080/api/9')).toBeInTheDocument();
    });
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  it('surfaces api errors without opening the modal', async () => {
    globalThis.fetch = vi.fn(
      async () =>
        new Response(JSON.stringify({ title: 'Invalid org', detail: 'name is required' }), {
          status: 400,
          headers: { 'Content-Type': 'application/problem+json' },
        }),
    ) as typeof fetch;

    renderPage();
    const user = userEvent.setup();
    await user.type(screen.getByLabelText(/Organization name/i), 'Acme');
    await user.type(screen.getByLabelText(/Project name/i), 'Web');
    await user.click(screen.getByRole('button', { name: /Create project/i }));

    await waitFor(() => {
      expect(screen.getByText('name is required')).toBeInTheDocument();
    });
    expect(screen.queryByText(/arguslog:\/\//)).not.toBeInTheDocument();
  });
});
