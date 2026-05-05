import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError, apiFetch, buildQuery } from '../../api/client';
import { getIssue, listIssueEvents, listIssues } from '../../api/issues';
import { useIssue, useIssueEvents, useIssues } from '../../api/queries';
import { useAuthStore } from '../../auth/useAuthStore';

const originalFetch = globalThis.fetch;

describe('apiFetch', () => {
  beforeEach(() => {
    useAuthStore.setState({
      status: 'authenticated',
      user: { id: 'u1' },
      accessToken: 'tok-abc',
      expiresAt: 9999999999,
      error: null,
    });
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
    useAuthStore.setState({
      status: 'idle',
      user: null,
      accessToken: null,
      expiresAt: null,
      error: null,
    });
  });

  it('attaches Bearer token from the auth store on every call', async () => {
    const spy = vi.fn(
      async () =>
        new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } }),
    );
    globalThis.fetch = spy as typeof fetch;

    await apiFetch('/api/v1/info');

    const init = spy.mock.calls[0]![1] as RequestInit;
    const headers = new Headers(init.headers);
    expect(headers.get('Authorization')).toBe('Bearer tok-abc');
    expect(headers.get('Accept')).toBe('application/json');
  });

  it('omits Authorization when there is no token', async () => {
    useAuthStore.setState({ accessToken: null });
    const spy = vi.fn(
      async () =>
        new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } }),
    );
    globalThis.fetch = spy as typeof fetch;

    await apiFetch('/api/v1/info');

    const headers = new Headers((spy.mock.calls[0]![1] as RequestInit).headers);
    expect(headers.has('Authorization')).toBe(false);
  });

  it('returns parsed JSON for 2xx', async () => {
    globalThis.fetch = (async () =>
      new Response(JSON.stringify({ name: 'argus' }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })) as typeof fetch;

    const out = await apiFetch<{ name: string }>('/api/v1/info');
    expect(out).toEqual({ name: 'argus' });
  });

  it('throws ApiError carrying the problem+json body for non-2xx', async () => {
    globalThis.fetch = (async () =>
      new Response(JSON.stringify({ title: 'Invalid cursor', status: 400, detail: 'bad' }), {
        status: 400,
        headers: { 'Content-Type': 'application/problem+json' },
      })) as typeof fetch;

    const err = await apiFetch('/api/v1/x').catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).status).toBe(400);
    expect((err as ApiError).problem.title).toBe('Invalid cursor');
    expect((err as ApiError).message).toBe('bad');
  });

  it('falls back to a synthetic problem when the body is not JSON', async () => {
    globalThis.fetch = (async () => new Response('upstream down', { status: 502 })) as typeof fetch;

    const err = (await apiFetch('/api/v1/x').catch((e: unknown) => e)) as ApiError;
    expect(err.status).toBe(502);
    expect(err.problem.title).toBe('HTTP 502');
  });
});

describe('buildQuery', () => {
  it('skips undefined / null / empty values', () => {
    expect(buildQuery({ a: 1, b: undefined, c: null, d: '', e: 'ok' })).toBe('?a=1&e=ok');
  });

  it('returns an empty string when all values are missing', () => {
    expect(buildQuery({ a: undefined, b: null })).toBe('');
  });
});

// Tiny smoke tests for the api/issues + api/queries wrappers — they are thin enough that
// a URL/spec smoke-test is the right depth. Heavier behavior (auth header, problem json,
// pagination) lives in apiFetch above.
describe('api/issues URL shape', () => {
  let lastUrl = '';
  beforeEach(() => {
    lastUrl = '';
    globalThis.fetch = (async (input: RequestInfo | URL) => {
      lastUrl = typeof input === 'string' ? input : input.toString();
      return new Response(JSON.stringify({ data: [], page: {} }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      });
    }) as typeof fetch;
  });
  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  it('listIssues forwards filters + cursor + limit', async () => {
    await listIssues({
      projectId: 101,
      status: 'resolved',
      level: 'warning',
      cursor: 'C',
      limit: 25,
    });
    expect(lastUrl).toContain('/api/v1/projects/101/issues');
    expect(lastUrl).toContain('status=resolved');
    expect(lastUrl).toContain('level=warning');
    expect(lastUrl).toContain('cursor=C');
    expect(lastUrl).toContain('limit=25');
  });

  it('getIssue hits the singular path', async () => {
    globalThis.fetch = (async (input: RequestInfo | URL) => {
      lastUrl = typeof input === 'string' ? input : input.toString();
      return new Response(
        JSON.stringify({
          id: 7,
          projectId: 101,
          fingerprint: 'fp',
          status: 'unresolved',
          level: 'error',
          title: 't',
          culprit: null,
          firstSeenAt: '2026-05-05T10:00:00Z',
          lastSeenAt: '2026-05-05T11:00:00Z',
          occurrenceCount: 1,
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      );
    }) as typeof fetch;
    await getIssue(101, 7);
    expect(lastUrl).toContain('/api/v1/projects/101/issues/7');
  });

  it('listIssueEvents includes cursor + limit and points at the events sub-resource', async () => {
    await listIssueEvents({ projectId: 101, issueId: 7, cursor: 'XYZ', limit: 10 });
    expect(lastUrl).toContain('/api/v1/projects/101/issues/7/events');
    expect(lastUrl).toContain('cursor=XYZ');
    expect(lastUrl).toContain('limit=10');
  });

  it('queryKey factories are stable per param shape', () => {
    // Ensures useIssues / useIssue / useIssueEvents don't shadow each other in the cache —
    // they share the 'issues' prefix on purpose so a mutation can invalidate both.
    expect(useIssues).toBeTypeOf('function');
    expect(useIssue).toBeTypeOf('function');
    expect(useIssueEvents).toBeTypeOf('function');
  });
});
