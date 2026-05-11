import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  getAdminStats,
  grantBonus,
  grantUserBonus,
  listAdminAudit,
  listAdminOrgs,
  listAdminUsers,
  revokeBonus,
  revokeUserBonus,
} from '../../api/admin';

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

describe('admin api client', () => {
  beforeEach(() => {
    globalThis.fetch = originalFetch;
  });
  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  it('getAdminStats calls /admin/stats', async () => {
    const f = mockFetch({ totalUsers: 1, totalOrgs: 1, totalProjects: 1, totalIssues: 1, orgsByPlan: {}, activeBonusGrants: 0, events7d: 0, events30d: 0 });
    await getAdminStats();
    expect(f.mock.calls[0]?.[0]).toContain('/api/v1/admin/stats');
  });

  it('listAdminUsers encodes search + offset + limit in query', async () => {
    const f = mockFetch({ items: [], total: 0, offset: 0, limit: 25 });
    await listAdminUsers({ q: 'alice', offset: 50, limit: 25 });
    const url = String(f.mock.calls[0]?.[0]);
    expect(url).toContain('/api/v1/admin/users');
    expect(url).toContain('q=alice');
    expect(url).toContain('offset=50');
    expect(url).toContain('limit=25');
  });

  it('listAdminUsers omits empty query params', async () => {
    const f = mockFetch({ items: [], total: 0, offset: 0, limit: 25 });
    await listAdminUsers({ q: '', offset: undefined, limit: 25 });
    const url = String(f.mock.calls[0]?.[0]);
    expect(url).not.toContain('q=');
    expect(url).not.toContain('offset=');
    expect(url).toContain('limit=25');
  });

  it('listAdminOrgs hits /admin/orgs', async () => {
    const f = mockFetch({ items: [], total: 0, offset: 0, limit: 25 });
    await listAdminOrgs({ q: 'acme' });
    expect(String(f.mock.calls[0]?.[0])).toContain('/api/v1/admin/orgs?q=acme');
  });

  it('listAdminAudit hits /admin/audit', async () => {
    const f = mockFetch({ items: [], total: 0, offset: 0, limit: 25 });
    await listAdminAudit({ offset: 0, limit: 50 });
    expect(String(f.mock.calls[0]?.[0])).toContain('/api/v1/admin/audit');
  });

  it('grantBonus POSTs to /admin/orgs/{id}/grant with the body', async () => {
    const f = mockFetch({});
    await grantBonus(42, { tier: 'pro', months: 3, reason: 'beta' });
    expect(f.mock.calls[0]?.[0]).toContain('/api/v1/admin/orgs/42/grant');
    const init = f.mock.calls[0]?.[1] as RequestInit;
    expect(init.method).toBe('POST');
    expect(JSON.parse(String(init.body))).toEqual({ tier: 'pro', months: 3, reason: 'beta' });
  });

  it('revokeBonus DELETEs the org grant', async () => {
    const f = mockFetch({});
    await revokeBonus(42);
    expect(f.mock.calls[0]?.[0]).toContain('/api/v1/admin/orgs/42/grant');
    expect((f.mock.calls[0]?.[1] as RequestInit).method).toBe('DELETE');
  });

  it('grantUserBonus POSTs to /admin/users/{id}/grant', async () => {
    const f = mockFetch({});
    await grantUserBonus('11111111-1111-1111-1111-111111111111', {
      tier: 'starter',
      months: 1,
      reason: 'trial',
    });
    expect(f.mock.calls[0]?.[0]).toContain('/api/v1/admin/users/11111111-1111-1111-1111-111111111111/grant',
    );
  });

  it('revokeUserBonus DELETEs the user grant', async () => {
    const f = mockFetch({});
    await revokeUserBonus('11111111-1111-1111-1111-111111111111');
    expect((f.mock.calls[0]?.[1] as RequestInit).method).toBe('DELETE');
  });
});
