import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { ArgusClient } from '../client.js';
import type { ArgusOptions, EventPayload } from '../types.js';

describe('ArgusClient', () => {
  let fetchMock: ReturnType<typeof vi.fn>;
  let sent: EventPayload[];

  beforeEach(() => {
    sent = [];
    fetchMock = vi.fn(async (_url: string, init?: RequestInit) => {
      sent.push(JSON.parse(init?.body as string) as EventPayload);
      return new Response(null, { status: 202 });
    });
  });

  afterEach(() => vi.restoreAllMocks());

  function makeClient(extra: Partial<ArgusOptions> = {}): ArgusClient {
    return new ArgusClient({
      dsn: 'arguslog://k@localhost:8080/api/1',
      environment: 'test',
      release: '0.0.0',
      transport: { fetch: fetchMock as unknown as typeof fetch, maxRetries: 0 },
      ...extra,
    });
  }

  it('captureException sends a structured event', async () => {
    const client = makeClient();
    const eventId = client.captureException(new Error('boom'));
    expect(eventId).toBeTruthy();
    await client.flush();
    expect(sent).toHaveLength(1);
    expect(sent[0]?.exception?.values[0]?.value).toBe('boom');
    expect(sent[0]?.environment).toBe('test');
    expect(sent[0]?.level).toBe('error');
  });

  it('captureMessage sends with provided level', async () => {
    const client = makeClient();
    client.captureMessage('hello', 'warning');
    await client.flush();
    expect(sent[0]?.message).toBe('hello');
    expect(sent[0]?.level).toBe('warning');
  });

  it('attaches user, tags and contexts', async () => {
    const client = makeClient();
    client.setUser({ id: 'u1', email: 'a@b.com' });
    client.setTag('env', 'qa');
    client.setContext('session', { id: 's1' });
    client.captureMessage('m');
    await client.flush();
    // email is auto-scrubbed before send
    expect(sent[0]?.user?.id).toBe('u1');
    expect(sent[0]?.user?.email).toBe('[Filtered]');
    expect(sent[0]?.tags?.env).toBe('qa');
    expect(sent[0]?.contexts?.session).toEqual({ id: 's1' });
  });

  it('breadcrumbs ring buffer respects max', async () => {
    const client = makeClient({ maxBreadcrumbs: 2 });
    client.addBreadcrumb({ category: 'ui', message: 'a', level: 'info' });
    client.addBreadcrumb({ category: 'ui', message: 'b', level: 'info' });
    client.addBreadcrumb({ category: 'ui', message: 'c', level: 'info' });
    client.captureMessage('m');
    await client.flush();
    expect(sent[0]?.breadcrumbs?.map((b) => b.message)).toEqual(['b', 'c']);
  });

  it('beforeSend can drop events', async () => {
    const client = makeClient({ beforeSend: () => null });
    client.captureMessage('dropped');
    await client.flush();
    expect(sent).toHaveLength(0);
  });

  it('beforeSend can mutate events', async () => {
    const client = makeClient({
      beforeSend: (e) => ({ ...e, tags: { ...(e.tags ?? {}), mutated: 'true' } }),
    });
    client.captureMessage('m');
    await client.flush();
    expect(sent[0]?.tags?.mutated).toBe('true');
  });

  it('sampleRate=0 drops all events', async () => {
    const client = makeClient({ sampleRate: 0 });
    client.captureMessage('m');
    await client.flush();
    expect(sent).toHaveLength(0);
  });

  it('sends DSN auth header', async () => {
    const client = makeClient();
    client.captureMessage('m');
    await client.flush();
    expect(fetchMock).toHaveBeenCalledWith(
      'http://localhost:8080/api/1/events',
      expect.objectContaining({
        headers: expect.objectContaining({ 'X-Arguslog-Auth': 'Arguslog DSN k' }),
      }),
    );
  });
});
