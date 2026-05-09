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

const PLANS = {
  currency: 'USD',
  free: { plan: 'free', monthlyEventCap: 5000, projectCap: 1, retentionDays: 30, durations: [] },
  pro: {
    plan: 'pro',
    monthlyEventCap: 100000,
    projectCap: 10,
    retentionDays: 30,
    durations: [
      { months: 1, amountCents: 1199, perMonthCents: 1199, savePercent: 0 },
      { months: 3, amountCents: 2999, perMonthCents: 999, savePercent: 17 },
      { months: 6, amountCents: 5399, perMonthCents: 899, savePercent: 25 },
      { months: 12, amountCents: 9599, perMonthCents: 799, savePercent: 33 },
    ],
  },
  enterprise: {
    plan: 'enterprise',
    monthlyEventCap: Number.MAX_SAFE_INTEGER,
    projectCap: 999,
    retentionDays: 365,
    durations: [],
  },
};

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

function mockFetch(handlers: Record<string, (init?: RequestInit) => Response>) {
  return vi.fn(async (input, init) => {
    const url = typeof input === 'string' ? input : (input as Request).url;
    for (const [pattern, handler] of Object.entries(handlers)) {
      if (url.includes(pattern)) return handler(init);
    }
    throw new Error(`unexpected fetch: ${url}`);
  }) as typeof fetch;
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

  it('renders the FREE plan with usage and the four duration cards', async () => {
    globalThis.fetch = mockFetch({
      '/api/v1/orgs/1/usage': () =>
        jsonResponse({
          plan: 'free',
          monthlyPriceCents: 0,
          eventsUsed: 1500,
          eventCap: 5000,
          projectCap: 1,
          retentionDays: 7,
          ratio: 0.3,
          exceeded: false,
          billingInterval: 'one_month',
        }),
      '/api/v1/billing/plans': () => jsonResponse(PLANS),
      '/api/v1/orgs': () => jsonResponse([ORG]),
    });

    renderAt();

    await waitFor(() => expect(screen.getByTestId('usage-ratio')).toBeInTheDocument());
    expect(screen.getByTestId('usage-ratio')).toHaveTextContent('1,500 / 5,000');
    expect(screen.getAllByText(/free/i).length).toBeGreaterThanOrEqual(1);
    // All four duration cards visible.
    expect(screen.getByTestId('duration-card-1')).toBeInTheDocument();
    expect(screen.getByTestId('duration-card-3')).toBeInTheDocument();
    expect(screen.getByTestId('duration-card-6')).toBeInTheDocument();
    expect(screen.getByTestId('duration-card-12')).toBeInTheDocument();
    // The 12-month card is highlighted as "Best value".
    expect(screen.getByTestId('duration-card-12')).toHaveTextContent(/best value/i);
    // Save badges reflect the aggressive ladder.
    expect(screen.getByTestId('duration-card-12')).toHaveTextContent('Save 33%');
    expect(screen.getByTestId('duration-card-3')).toHaveTextContent('Save 17%');
    // Total prices.
    expect(screen.getByTestId('duration-card-1')).toHaveTextContent('$11.99');
    expect(screen.getByTestId('duration-card-12')).toHaveTextContent('$95.99');

    // The "what you get" panel is rendered before the cards so visitors know
    // what they're paying for. Quotas come from the API (server-driven).
    const features = screen.getByTestId('pro-features-panel');
    expect(features).toHaveTextContent('100,000 events');
    expect(features).toHaveTextContent('20×');
    expect(features).toHaveTextContent('10 projects');
    expect(features).toHaveTextContent(/no auto-renewal/i);
  });

  it('redirects to the NOWPayments checkout url when a duration card is picked', async () => {
    const assign = vi.fn();
    Object.defineProperty(window, 'location', {
      value: { ...originalLocation, assign },
      writable: true,
      configurable: true,
    });

    let cryptoUrl: string | undefined;
    globalThis.fetch = mockFetch({
      '/api/v1/orgs/1/usage': () =>
        jsonResponse({
          plan: 'free',
          monthlyPriceCents: 0,
          eventsUsed: 0,
          eventCap: 5000,
          projectCap: 1,
          retentionDays: 7,
          ratio: 0,
          exceeded: false,
          billingInterval: 'one_month',
        }),
      '/api/v1/billing/plans': () => jsonResponse(PLANS),
      '/api/v1/orgs/1/billing/crypto-invoice': (init) => {
        if (init?.method !== 'POST') throw new Error('expected POST');
        return jsonResponse({
          checkoutUrl: 'https://nowpayments.io/payment/iv_abc',
          invoiceReference: 'ref-1',
        });
      },
      '/api/v1/orgs': () => jsonResponse([ORG]),
    });

    // Capture the URL to assert duration query param.
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
          billingInterval: 'one_month',
        });
      }
      if (url.endsWith('/api/v1/billing/plans')) return jsonResponse(PLANS);
      if (url.includes('/api/v1/orgs/1/billing/crypto-invoice') && init?.method === 'POST') {
        cryptoUrl = url;
        return jsonResponse({
          checkoutUrl: 'https://nowpayments.io/payment/iv_abc',
          invoiceReference: 'ref-1',
        });
      }
      throw new Error(`unexpected fetch: ${url}`);
    }) as typeof fetch;

    renderAt();

    const sixMonthBtn = await screen.findByTestId('pay-crypto-6');
    await userEvent.click(sixMonthBtn);

    await waitFor(() =>
      expect(assign).toHaveBeenCalledWith('https://nowpayments.io/payment/iv_abc'),
    );
    expect(cryptoUrl).toContain('duration=6');
  });

  it('hides duration grid for an active Pro org with renewal in the future', async () => {
    const renewsAt = new Date(Date.now() + 60 * 24 * 3600 * 1000).toISOString();
    globalThis.fetch = mockFetch({
      '/api/v1/orgs/1/usage': () =>
        jsonResponse({
          plan: 'pro',
          monthlyPriceCents: 1199,
          eventsUsed: 12000,
          eventCap: 100000,
          projectCap: 10,
          retentionDays: 30,
          ratio: 0.12,
          exceeded: false,
          billingInterval: 'twelve_months',
          renewsAt,
        }),
      '/api/v1/billing/plans': () => jsonResponse(PLANS),
      '/api/v1/orgs': () => jsonResponse([{ ...ORG, plan: 'pro' }]),
    });

    renderAt();

    await waitFor(() => expect(screen.getByTestId('renews-at')).toBeInTheDocument());
    expect(screen.queryByTestId('duration-card-1')).not.toBeInTheDocument();
    expect(screen.queryByTestId('renew-banner')).not.toBeInTheDocument();
  });

  it('shows the renew banner and extend grid when Pro plan expires within 14 days', async () => {
    const renewsAt = new Date(Date.now() + 5 * 24 * 3600 * 1000).toISOString();
    globalThis.fetch = mockFetch({
      '/api/v1/orgs/1/usage': () =>
        jsonResponse({
          plan: 'pro',
          monthlyPriceCents: 1199,
          eventsUsed: 1000,
          eventCap: 100000,
          projectCap: 10,
          retentionDays: 30,
          ratio: 0.01,
          exceeded: false,
          billingInterval: 'one_month',
          renewsAt,
        }),
      '/api/v1/billing/plans': () => jsonResponse(PLANS),
      '/api/v1/orgs': () => jsonResponse([{ ...ORG, plan: 'pro' }]),
    });

    renderAt();

    const banner = await screen.findByTestId('renew-banner');
    expect(banner).toHaveTextContent(/expires in 5/i);
    expect(screen.getByTestId('duration-card-12')).toBeInTheDocument();
  });

  it('shows the payment-grace banner with countdown when the api returns a deadline', async () => {
    const deadline = new Date(Date.now() + 5 * 24 * 3600 * 1000).toISOString();
    globalThis.fetch = mockFetch({
      '/api/v1/orgs/1/usage': () =>
        jsonResponse({
          plan: 'pro',
          monthlyPriceCents: 1199,
          eventsUsed: 100,
          eventCap: 100000,
          projectCap: 10,
          retentionDays: 30,
          ratio: 0.001,
          exceeded: false,
          billingInterval: 'one_month',
          paymentGraceUntil: deadline,
        }),
      '/api/v1/billing/plans': () => jsonResponse(PLANS),
      '/api/v1/orgs': () => jsonResponse([{ ...ORG, plan: 'pro' }]),
    });

    renderAt();

    const banner = await screen.findByTestId('payment-grace-banner');
    expect(banner).toHaveTextContent(/payment failed/i);
    expect(banner).toHaveTextContent(/5/);
  });

  it('shows the cap-exceeded banner when the api flags it', async () => {
    globalThis.fetch = mockFetch({
      '/api/v1/orgs/1/usage': () =>
        jsonResponse({
          plan: 'free',
          monthlyPriceCents: 0,
          eventsUsed: 5200,
          eventCap: 5000,
          projectCap: 1,
          retentionDays: 7,
          ratio: 1.04,
          exceeded: true,
          billingInterval: 'one_month',
        }),
      '/api/v1/billing/plans': () => jsonResponse(PLANS),
      '/api/v1/orgs': () => jsonResponse([ORG]),
    });

    renderAt();

    await waitFor(() => expect(screen.getByText(/cap exceeded/i)).toBeInTheDocument());
  });
});
