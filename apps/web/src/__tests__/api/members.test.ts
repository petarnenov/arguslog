import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  changeOrgMemberRole,
  inviteOrgMember,
  listOrgMembers,
  removeOrgMember,
} from '../../api/members';

const originalFetch = globalThis.fetch;

function mockFetch(body: unknown = {}): ReturnType<typeof vi.fn> {
  const fetchMock = vi.fn(
    async () =>
      new Response(JSON.stringify(body), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
  );
  globalThis.fetch = fetchMock as typeof fetch;
  return fetchMock;
}

describe('members api client', () => {
  beforeEach(() => {
    globalThis.fetch = originalFetch;
  });
  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  it('listOrgMembers hits /orgs/{id}/members', async () => {
    const f = mockFetch([]);
    await listOrgMembers(42);
    expect(f.mock.calls[0]?.[0]).toContain('/api/v1/orgs/42/members');
  });

  it('inviteOrgMember POSTs the email + role', async () => {
    const f = mockFetch({});
    await inviteOrgMember(42, { email: 'a@b.c', role: 'member' });
    expect(f.mock.calls[0]?.[0]).toContain('/api/v1/orgs/42/members');
    const init = f.mock.calls[0]?.[1] as RequestInit;
    expect(init.method).toBe('POST');
    expect(JSON.parse(String(init.body))).toEqual({ email: 'a@b.c', role: 'member' });
  });

  it('changeOrgMemberRole PATCHes the role on the member id', async () => {
    const f = mockFetch({});
    await changeOrgMemberRole(42, 'uuid-1', 'admin');
    expect(f.mock.calls[0]?.[0]).toContain('/api/v1/orgs/42/members/uuid-1');
    const init = f.mock.calls[0]?.[1] as RequestInit;
    expect(init.method).toBe('PATCH');
    expect(JSON.parse(String(init.body))).toEqual({ role: 'admin' });
  });

  it('removeOrgMember DELETEs the member', async () => {
    const f = mockFetch({});
    await removeOrgMember(42, 'uuid-2');
    expect(f.mock.calls[0]?.[0]).toContain('/api/v1/orgs/42/members/uuid-2');
    expect((f.mock.calls[0]?.[1] as RequestInit).method).toBe('DELETE');
  });
});
