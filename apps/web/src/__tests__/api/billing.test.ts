import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  getBillingPlans,
  openMePortal,
  startMeCheckout,
  startMeCryptoCheckout,
} from '../../api/billing';

const originalFetch = globalThis.fetch;

function mockFetch(body: unknown = {}, status = 200): ReturnType<typeof vi.fn> {
  const fetchMock = vi.fn(
    async () =>
      new Response(JSON.stringify(body), {
        status,
        headers: { 'Content-Type': 'application/json' },
      }),
  );
  globalThis.fetch = fetchMock as typeof fetch;
  return fetchMock;
}

describe('billing api client', () => {
  beforeEach(() => {
    globalThis.fetch = originalFetch;
  });
  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  it('getBillingPlans hits the catalog endpoint', async () => {
    const f = mockFetch({ currency: 'USD', tiers: [] });
    await getBillingPlans();
    expect(f.mock.calls[0]?.[0]).toContain('/api/v1/billing/plans');
  });

  it('startMeCheckout defaults to monthly when no interval is passed', async () => {
    const f = mockFetch({ url: 'https://checkout.stripe.com/x' });
    await startMeCheckout();
    const url = String(f.mock.calls[0]?.[0]);
    expect(url).toContain('/api/v1/me/billing/checkout-session?interval=monthly');
    expect((f.mock.calls[0]?.[1] as RequestInit).method).toBe('POST');
  });

  it('startMeCheckout respects the annual interval', async () => {
    const f = mockFetch({ url: 'x' });
    await startMeCheckout('annual');
    expect(String(f.mock.calls[0]?.[0])).toContain(
      '/api/v1/me/billing/checkout-session?interval=annual',
    );
  });

  it('startMeCryptoCheckout encodes tier + duration', async () => {
    const f = mockFetch({ checkoutUrl: 'x', invoiceReference: 'ref' });
    await startMeCryptoCheckout('pro', 6);
    expect(String(f.mock.calls[0]?.[0])).toContain(
      '/api/v1/me/billing/crypto-invoice?tier=pro&duration=6',
    );
  });

  it('openMePortal POSTs to /me/billing/portal', async () => {
    const f = mockFetch({ url: 'https://billing.stripe.com/p' });
    await openMePortal();
    expect(f.mock.calls[0]?.[0]).toContain('/api/v1/me/billing/portal');
    expect((f.mock.calls[0]?.[1] as RequestInit).method).toBe('POST');
  });
});
