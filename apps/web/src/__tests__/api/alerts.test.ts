import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  createAlertDestination,
  createAlertRule,
  deleteAlertDestination,
  deleteAlertRule,
  listAlertDestinations,
  listAlertRules,
  updateAlertDestination,
  updateAlertRule,
} from '../../api/alerts';

const originalFetch = globalThis.fetch;
function mockFetch(body: unknown = {}): ReturnType<typeof vi.fn> {
  const f = vi.fn(async () => new Response(JSON.stringify(body), { status: 200 }));
  globalThis.fetch = f as typeof fetch;
  return f;
}

describe('alerts api client', () => {
  beforeEach(() => {
    globalThis.fetch = originalFetch;
  });
  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  it('listAlertDestinations GETs /orgs/{id}/alert-destinations', async () => {
    const f = mockFetch([]);
    await listAlertDestinations(42);
    expect(f.mock.calls[0]?.[0]).toContain('/api/v1/orgs/42/alert-destinations');
  });

  it('createAlertDestination POSTs config', async () => {
    const f = mockFetch({});
    await createAlertDestination(42, { kind: 'slack', name: 'eng', config: { url: 'x' } });
    const init = f.mock.calls[0]?.[1] as RequestInit;
    expect(init.method).toBe('POST');
    expect(JSON.parse(String(init.body))).toMatchObject({ kind: 'slack', name: 'eng' });
  });

  it('updateAlertDestination PUTs to the id', async () => {
    const f = mockFetch({});
    await updateAlertDestination(42, 7, { kind: 'email', name: 'oncall', config: {} });
    expect(f.mock.calls[0]?.[0]).toContain('/api/v1/orgs/42/alert-destinations/7');
    expect((f.mock.calls[0]?.[1] as RequestInit).method).toBe('PUT');
  });

  it('deleteAlertDestination DELETEs', async () => {
    const f = mockFetch({});
    await deleteAlertDestination(42, 7);
    expect((f.mock.calls[0]?.[1] as RequestInit).method).toBe('DELETE');
  });

  it('listAlertRules GETs /projects/{id}/alert-rules', async () => {
    const f = mockFetch([]);
    await listAlertRules(9);
    expect(f.mock.calls[0]?.[0]).toContain('/api/v1/projects/9/alert-rules');
  });

  it('createAlertRule + updateAlertRule + deleteAlertRule round-trip the ids', async () => {
    let f = mockFetch({});
    await createAlertRule(9, {
      name: 'r',
      conditions: {},
      actions: {},
      throttleSeconds: 60,
      enabled: true,
    });
    expect(f.mock.calls[0]?.[0]).toContain('/api/v1/projects/9/alert-rules');
    f = mockFetch({});
    await updateAlertRule(9, 3, {
      name: 'r',
      conditions: {},
      actions: {},
      throttleSeconds: 60,
      enabled: true,
    });
    expect(f.mock.calls[0]?.[0]).toContain('/api/v1/projects/9/alert-rules/3');
    expect((f.mock.calls[0]?.[1] as RequestInit).method).toBe('PUT');
    f = mockFetch({});
    await deleteAlertRule(9, 3);
    expect((f.mock.calls[0]?.[1] as RequestInit).method).toBe('DELETE');
  });
});
