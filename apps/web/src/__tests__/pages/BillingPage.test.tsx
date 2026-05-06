import { MantineProvider } from '@mantine/core';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import '../../i18n';
import { BillingPage } from '../../pages/BillingPage';

const originalFetch = globalThis.fetch;
const originalLocation = window.location;

const ORG = { id: 1, slug: 'acme', name: 'Acme', plan: 'free', createdAt: '2026-05-01T00:00:00Z' };

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function renderAt(path = '/orgs/acme/billing') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <MantineProvider>
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={[path]}>
          <Routes>
            <Route path="/orgs/:orgSlug/billing" element={<BillingPage />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>
    </MantineProvider>,
  );
}

describe('BillingPage', () => {
  beforeEach(() => {
    globalThis.fetch = originalFetch;
  });
  afterEach(() => {
    globalThis.fetch = originalFetch;
    Object.defineProperty(window, 'location', {
      value: originalLocation,
      writable: true,
      configurable: true,
    });
  });

  it('renders the FREE plan with usage and an Upgrade CTA', async () => {
    globalThis.fetch = vi.fn(async (input) => {
      const url = typeof input === 'string' ? input : (input as Request).url;
      if (url.endsWith('/api/v1/orgs')) return jsonResponse([ORG]);
      if (url.endsWith('/api/v1/orgs/1/usage')) {
        return jsonResponse({
          plan: 'free',
          monthlyPriceCents: 0,
          eventsUsed: 1500,
          eventCap: 5000,
          projectCap: 1,
          retentionDays: 7,
          ratio: 0.3,
          exceeded: false,
        });
      }
      throw new Error(`unexpected fetch: ${url}`);
    }) as typeof fetch;

    renderAt();

    await waitFor(() => expect(screen.getByText(/free/i)).toBeInTheDocument());
    expect(screen.getByTestId('usage-ratio')).toHaveTextContent('1,500 / 5,000');
    expect(screen.getByTestId('upgrade-button')).toBeInTheDocument();
  });

  it('redirects to the Stripe checkout url when Upgrade is clicked', async () => {
    const assign = vi.fn();
    Object.defineProperty(window, 'location', {
      value: { ...originalLocation, assign },
      writable: true,
      configurable: true,
    });

    globalThis.fetch = vi.fn(async (input, init) => {
      const url = typeof input === 'string' ? input : (input as Request).url;
      if (url.endsWith('/api/v1/orgs')) return jsonResponse([ORG]);
      if (url.endsWith('/api/v1/orgs/1/usage')) {
        return jsonResponse({
          plan: 'free',
          monthlyPriceCents: 0,
          eventsUsed: 0,
          eventCap: 5000,
          projectCap: 1,
          retentionDays: 7,
          ratio: 0,
          exceeded: false,
        });
      }
      if (url.endsWith('/api/v1/orgs/1/billing/checkout-session') && init?.method === 'POST') {
        return jsonResponse({ url: 'https://checkout.stripe.com/c/sess_abc' });
      }
      throw new Error(`unexpected fetch: ${url}`);
    }) as typeof fetch;

    renderAt();

    const upgrade = await screen.findByTestId('upgrade-button');
    await userEvent.click(upgrade);

    await waitFor(() =>
      expect(assign).toHaveBeenCalledWith('https://checkout.stripe.com/c/sess_abc'),
    );
  });

  it('renders the PRO plan with a Manage subscription CTA', async () => {
    globalThis.fetch = vi.fn(async (input) => {
      const url = typeof input === 'string' ? input : (input as Request).url;
      if (url.endsWith('/api/v1/orgs')) return jsonResponse([{ ...ORG, plan: 'pro' }]);
      if (url.endsWith('/api/v1/orgs/1/usage')) {
        return jsonResponse({
          plan: 'pro',
          monthlyPriceCents: 900,
          eventsUsed: 12000,
          eventCap: 100000,
          projectCap: 10,
          retentionDays: 30,
          ratio: 0.12,
          exceeded: false,
        });
      }
      throw new Error(`unexpected fetch: ${url}`);
    }) as typeof fetch;

    renderAt();

    await waitFor(() => expect(screen.getByText(/\$9\/mo/)).toBeInTheDocument());
    expect(screen.getByTestId('manage-button')).toBeInTheDocument();
    expect(screen.queryByTestId('upgrade-button')).not.toBeInTheDocument();
  });

  it('shows the payment-grace banner with countdown when the api returns a deadline', async () => {
    const deadline = new Date(Date.now() + 5 * 24 * 3600 * 1000).toISOString();
    globalThis.fetch = vi.fn(async (input) => {
      const url = typeof input === 'string' ? input : (input as Request).url;
      if (url.endsWith('/api/v1/orgs')) return jsonResponse([{ ...ORG, plan: 'pro' }]);
      if (url.endsWith('/api/v1/orgs/1/usage')) {
        return jsonResponse({
          plan: 'pro',
          monthlyPriceCents: 900,
          eventsUsed: 100,
          eventCap: 100000,
          projectCap: 10,
          retentionDays: 30,
          ratio: 0.001,
          exceeded: false,
          paymentGraceUntil: deadline,
        });
      }
      throw new Error(`unexpected fetch: ${url}`);
    }) as typeof fetch;

    renderAt();

    const banner = await screen.findByTestId('payment-grace-banner');
    expect(banner).toHaveTextContent(/payment failed/i);
    // Math.ceil on 5d - epsilon → "5 day(s) remaining"
    expect(banner).toHaveTextContent(/5/);
  });

  it('opens the Stripe portal when Update payment method is clicked', async () => {
    const assign = vi.fn();
    Object.defineProperty(window, 'location', {
      value: { ...originalLocation, assign },
      writable: true,
      configurable: true,
    });
    const deadline = new Date(Date.now() + 3 * 24 * 3600 * 1000).toISOString();
    globalThis.fetch = vi.fn(async (input, init) => {
      const url = typeof input === 'string' ? input : (input as Request).url;
      if (url.endsWith('/api/v1/orgs')) return jsonResponse([{ ...ORG, plan: 'pro' }]);
      if (url.endsWith('/api/v1/orgs/1/usage')) {
        return jsonResponse({
          plan: 'pro',
          monthlyPriceCents: 900,
          eventsUsed: 0,
          eventCap: 100000,
          projectCap: 10,
          retentionDays: 30,
          ratio: 0,
          exceeded: false,
          paymentGraceUntil: deadline,
        });
      }
      if (url.endsWith('/api/v1/orgs/1/billing/portal') && init?.method === 'POST') {
        return jsonResponse({ url: 'https://billing.stripe.com/p/sess_grace' });
      }
      throw new Error(`unexpected fetch: ${url}`);
    }) as typeof fetch;

    renderAt();

    const update = await screen.findByTestId('update-payment-button');
    await userEvent.click(update);

    await waitFor(() =>
      expect(assign).toHaveBeenCalledWith('https://billing.stripe.com/p/sess_grace'),
    );
  });

  it('shows the cap-exceeded banner when the api flags it', async () => {
    globalThis.fetch = vi.fn(async (input) => {
      const url = typeof input === 'string' ? input : (input as Request).url;
      if (url.endsWith('/api/v1/orgs')) return jsonResponse([ORG]);
      if (url.endsWith('/api/v1/orgs/1/usage')) {
        return jsonResponse({
          plan: 'free',
          monthlyPriceCents: 0,
          eventsUsed: 5200,
          eventCap: 5000,
          projectCap: 1,
          retentionDays: 7,
          ratio: 1.04,
          exceeded: true,
        });
      }
      throw new Error(`unexpected fetch: ${url}`);
    }) as typeof fetch;

    renderAt();

    await waitFor(() => expect(screen.getByText(/cap exceeded/i)).toBeInTheDocument());
  });
});
