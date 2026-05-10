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
  tiers: [
    {
      plan: 'free',
      monthlyPriceCents: 0,
      monthlyEventCap: 5000,
      projectCap: 1,
      memberCap: 1,
      orgCap: 1,
      retentionDays: 30,
      unlimitedEvents: false,
      unlimitedProjects: false,
      unlimitedMembers: false,
      unlimitedOrgs: false,
      durations: [],
    },
    {
      plan: 'starter',
      monthlyPriceCents: 1199,
      monthlyEventCap: 25000,
      projectCap: 3,
      memberCap: 3,
      orgCap: 3,
      retentionDays: 30,
      unlimitedEvents: false,
      unlimitedProjects: false,
      unlimitedMembers: false,
      unlimitedOrgs: false,
      durations: [
        { months: 1, amountCents: 1199, perMonthCents: 1199, savePercent: 0 },
        { months: 3, amountCents: 2999, perMonthCents: 999, savePercent: 17 },
        { months: 6, amountCents: 5399, perMonthCents: 899, savePercent: 25 },
        { months: 12, amountCents: 9599, perMonthCents: 799, savePercent: 33 },
      ],
    },
    {
      plan: 'pro',
      monthlyPriceCents: 2999,
      monthlyEventCap: 100000,
      projectCap: 10,
      memberCap: 10,
      orgCap: 10,
      retentionDays: 90,
      unlimitedEvents: false,
      unlimitedProjects: false,
      unlimitedMembers: false,
      unlimitedOrgs: false,
      durations: [
        { months: 1, amountCents: 2999, perMonthCents: 2999, savePercent: 0 },
        { months: 3, amountCents: 7499, perMonthCents: 2499, savePercent: 17 },
        { months: 6, amountCents: 13499, perMonthCents: 2249, savePercent: 25 },
        { months: 12, amountCents: 23999, perMonthCents: 1999, savePercent: 33 },
      ],
    },
    {
      plan: 'business',
      monthlyPriceCents: 7999,
      monthlyEventCap: Number.MAX_SAFE_INTEGER,
      projectCap: 999999,
      memberCap: 999999,
      orgCap: 999999,
      retentionDays: 365,
      unlimitedEvents: true,
      unlimitedProjects: true,
      unlimitedMembers: true,
      unlimitedOrgs: true,
      durations: [
        { months: 1, amountCents: 7999, perMonthCents: 7999, savePercent: 0 },
        { months: 3, amountCents: 19999, perMonthCents: 6666, savePercent: 17 },
        { months: 6, amountCents: 35999, perMonthCents: 5999, savePercent: 25 },
        { months: 12, amountCents: 63999, perMonthCents: 5333, savePercent: 33 },
      ],
    },
  ],
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

function freeUsage() {
  return {
    plan: 'free',
    monthlyPriceCents: 0,
    eventsUsed: 1500,
    eventCap: 5000,
    projectCap: 1,
    retentionDays: 30,
    ratio: 0.3,
    exceeded: false,
    billingInterval: 'one_month',
  };
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

  it('renders all four tier cards with their caps', async () => {
    globalThis.fetch = vi.fn(async (input) => {
      const url = typeof input === 'string' ? input : (input as Request).url;
      if (url.endsWith('/api/v1/orgs')) return jsonResponse([ORG]);
      if (url.endsWith('/api/v1/orgs/1/usage')) return jsonResponse(freeUsage());
      if (url.endsWith('/api/v1/billing/plans')) return jsonResponse(PLANS);
      throw new Error(`unexpected fetch: ${url}`);
    }) as typeof fetch;

    renderAt();

    await waitFor(() => expect(screen.getByTestId('tier-card-free')).toBeInTheDocument());
    expect(screen.getByTestId('tier-card-starter')).toBeInTheDocument();
    expect(screen.getByTestId('tier-card-pro')).toBeInTheDocument();
    expect(screen.getByTestId('tier-card-business')).toBeInTheDocument();

    // Caps are visible on each card.
    expect(screen.getByTestId('tier-card-free')).toHaveTextContent('5,000 events');
    expect(screen.getByTestId('tier-card-starter')).toHaveTextContent('25,000 events');
    expect(screen.getByTestId('tier-card-pro')).toHaveTextContent('100,000 events');
    expect(screen.getByTestId('tier-card-business')).toHaveTextContent(/unlimited events/i);

    // Pro's retention is 90 days, business 365 — proves we read from server config.
    expect(screen.getByTestId('tier-card-pro')).toHaveTextContent('90-day');
    expect(screen.getByTestId('tier-card-business')).toHaveTextContent('365-day');

    // Pro is the most-popular tag.
    expect(screen.getByTestId('tier-card-pro')).toHaveTextContent(/most popular/i);
  });

  it('expands duration picker + Pay button only after a paid tier is selected', async () => {
    globalThis.fetch = vi.fn(async (input) => {
      const url = typeof input === 'string' ? input : (input as Request).url;
      if (url.endsWith('/api/v1/orgs')) return jsonResponse([ORG]);
      if (url.endsWith('/api/v1/orgs/1/usage')) return jsonResponse(freeUsage());
      if (url.endsWith('/api/v1/billing/plans')) return jsonResponse(PLANS);
      throw new Error(`unexpected fetch: ${url}`);
    }) as typeof fetch;

    renderAt();

    // Before selection, no expanded panel exists.
    await waitFor(() => expect(screen.getByTestId('tier-card-pro')).toBeInTheDocument());
    expect(screen.queryByTestId('tier-card-pro-expanded')).not.toBeInTheDocument();

    // Click the tier card to select it.
    await userEvent.click(screen.getByTestId('tier-card-pro'));

    // Now the expanded panel exists with the Pay CTA defaulting to 12 months.
    expect(await screen.findByTestId('tier-card-pro-expanded')).toBeInTheDocument();
    expect(screen.getByTestId('pay-pro-12')).toBeInTheDocument();
    expect(screen.getByTestId('pay-pro-12')).toHaveTextContent('$239.99');
  });

  it('starts NOWPayments crypto checkout with the chosen tier and duration', async () => {
    const assign = vi.fn();
    Object.defineProperty(window, 'location', {
      value: { ...originalLocation, assign },
      writable: true,
      configurable: true,
    });

    let capturedUrl: string | undefined;
    globalThis.fetch = vi.fn(async (input, init) => {
      const url = typeof input === 'string' ? input : (input as Request).url;
      if (url.endsWith('/api/v1/orgs')) return jsonResponse([ORG]);
      if (url.endsWith('/api/v1/orgs/1/usage')) return jsonResponse(freeUsage());
      if (url.endsWith('/api/v1/billing/plans')) return jsonResponse(PLANS);
      if (url.includes('/api/v1/orgs/1/billing/crypto-invoice') && init?.method === 'POST') {
        capturedUrl = url;
        return jsonResponse({
          checkoutUrl: 'https://nowpayments.io/payment/iv_starter6',
          invoiceReference: 'ref-1',
        });
      }
      throw new Error(`unexpected fetch: ${url}`);
    }) as typeof fetch;

    renderAt();

    await userEvent.click(await screen.findByTestId('tier-card-starter'));
    // Default is 12 months; pick 6 explicitly to prove the segmented control flows through.
    await userEvent.click(screen.getByRole('radio', { name: /6mo/i }));
    await userEvent.click(screen.getByTestId('pay-starter-6'));

    await waitFor(() =>
      expect(assign).toHaveBeenCalledWith('https://nowpayments.io/payment/iv_starter6'),
    );
    expect(capturedUrl).toContain('tier=starter');
    expect(capturedUrl).toContain('duration=6');
  });

  it('marks the org current plan with a Current badge', async () => {
    globalThis.fetch = vi.fn(async (input) => {
      const url = typeof input === 'string' ? input : (input as Request).url;
      if (url.endsWith('/api/v1/orgs')) return jsonResponse([{ ...ORG, plan: 'pro' }]);
      if (url.endsWith('/api/v1/orgs/1/usage'))
        return jsonResponse({
          ...freeUsage(),
          plan: 'pro',
          monthlyPriceCents: 2999,
          eventCap: 100000,
          projectCap: 10,
          retentionDays: 90,
          renewsAt: new Date(Date.now() + 60 * 24 * 3600 * 1000).toISOString(),
        });
      if (url.endsWith('/api/v1/billing/plans')) return jsonResponse(PLANS);
      throw new Error(`unexpected fetch: ${url}`);
    }) as typeof fetch;

    renderAt();

    await waitFor(() => expect(screen.getByTestId('tier-card-pro')).toBeInTheDocument());
    expect(screen.getByTestId('tier-card-pro')).toHaveTextContent(/current plan/i);
  });

  it('shows the cap-exceeded banner when the api flags it', async () => {
    globalThis.fetch = vi.fn(async (input) => {
      const url = typeof input === 'string' ? input : (input as Request).url;
      if (url.endsWith('/api/v1/orgs')) return jsonResponse([ORG]);
      if (url.endsWith('/api/v1/orgs/1/usage'))
        return jsonResponse({
          ...freeUsage(),
          eventsUsed: 5200,
          ratio: 1.04,
          exceeded: true,
        });
      if (url.endsWith('/api/v1/billing/plans')) return jsonResponse(PLANS);
      throw new Error(`unexpected fetch: ${url}`);
    }) as typeof fetch;

    renderAt();

    await waitFor(() => expect(screen.getByText(/cap exceeded/i)).toBeInTheDocument());
  });
});
