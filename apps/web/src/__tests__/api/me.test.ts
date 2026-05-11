import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { getMe } from '../../api/me';

const originalFetch = globalThis.fetch;

describe('me api client', () => {
  beforeEach(() => {
    globalThis.fetch = originalFetch;
  });
  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  it('GETs /api/v1/me and returns the typed payload', async () => {
    const body = {
      userId: '11111111-1111-1111-1111-111111111111',
      email: 'a@b.c',
      displayName: 'Alice',
      isPlatformAdmin: false,
      plan: 'pro',
      planRenewsAt: '2026-07-01T00:00:00Z',
      paymentGraceUntil: null,
      bonusUntil: null,
      bonusReason: null,
    };
    globalThis.fetch = vi.fn(
      async () => new Response(JSON.stringify(body), { status: 200 }),
    ) as typeof fetch;

    const me = await getMe();

    expect(me).toEqual(body);
    expect((globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0]?.[0]).toContain('/api/v1/me');
  });
});
