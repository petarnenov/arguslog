import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { run } from '../cli.js';
import { deriveIngestUrl } from '../commands/ping.js';

const FAKE_CONFIG = {
  token: 'arglog_pat_FAKE',
  apiBaseUrl: 'https://api.arguslog.org',
};

describe('deriveIngestUrl', () => {
  it('swaps api.<host> → ingest.<host>', () => {
    expect(deriveIngestUrl('https://api.arguslog.org')).toBe('https://ingest.arguslog.org');
  });

  it('swaps 8081 → 8080 for local dev', () => {
    expect(deriveIngestUrl('http://localhost:8081')).toBe('http://localhost:8080');
  });

  it('passes through unrecognised shape (operator should ARGUSLOG_INGEST_URL explicitly)', () => {
    expect(deriveIngestUrl('https://my-custom-api.example.com')).toBe(
      'https://my-custom-api.example.com',
    );
  });

  it('survives a malformed URL', () => {
    expect(deriveIngestUrl('not a url')).toBe('not a url');
  });
});

describe('arguslog ping', () => {
  const origFetch = globalThis.fetch;

  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
    globalThis.fetch = origFetch;
  });

  it('rejects without --project', async () => {
    const r = await run(['ping'], { loadConfig: () => FAKE_CONFIG });
    expect(r.exitCode).toBe(2);
    expect(r.stderr).toContain('--project <id> is required');
  });

  it('happy path: lists DSNs, posts to ingest, returns success line', async () => {
    const calls: Array<{ url: string; init: RequestInit }> = [];
    globalThis.fetch = vi.fn(async (input: string | URL, init?: RequestInit) => {
      const url = typeof input === 'string' ? input : input.toString();
      calls.push({ url, init: init ?? {} });
      if (url.endsWith('/keys')) {
        return new Response(
          JSON.stringify([
            {
              id: 1,
              projectId: 42,
              dsnPublic: 'PUBKEY123',
              active: true,
              createdAt: '2026-05-14T00:00:00Z',
            },
          ]),
          { status: 200, headers: { 'content-type': 'application/json' } },
        );
      }
      if (url.includes('/api/42/events')) {
        return new Response(JSON.stringify({ ok: true }), {
          status: 202,
          headers: { 'content-type': 'application/json' },
        });
      }
      return new Response('not found', { status: 404 });
    }) as typeof fetch;

    const r = await run(['ping', '--project', '42'], { loadConfig: () => FAKE_CONFIG });
    expect(r.exitCode).toBe(0);
    expect(r.stdout).toContain('ingest accepted synthetic event');
    expect(r.stdout).toContain('DSN PUBKEY12');

    // Verify the actual HTTP calls:
    expect(calls).toHaveLength(2);
    expect(calls[0]!.url).toContain('/api/v1/projects/42/keys');
    expect(calls[1]!.url).toBe('https://ingest.arguslog.org/api/42/events');
    const ingestHeaders = new Headers(calls[1]!.init.headers ?? {});
    expect(ingestHeaders.get('X-Arguslog-Auth')).toBe('Arguslog DSN PUBKEY123');
  });

  it('surfaces a friendly error when no active DSN exists', async () => {
    globalThis.fetch = vi.fn(
      async () =>
        new Response('[]', { status: 200, headers: { 'content-type': 'application/json' } }),
    ) as typeof fetch;

    const r = await run(['ping', '--project', '42'], { loadConfig: () => FAKE_CONFIG });
    expect(r.exitCode).toBe(1);
    expect(r.stderr).toContain('no active DSN');
  });

  it('surfaces a friendly error when ingest rejects', async () => {
    globalThis.fetch = vi.fn(async (input: string | URL) => {
      const url = typeof input === 'string' ? input : input.toString();
      if (url.endsWith('/keys')) {
        return new Response(
          JSON.stringify([
            {
              id: 1,
              projectId: 42,
              dsnPublic: 'PUBKEY123',
              active: true,
              createdAt: '2026-05-14T00:00:00Z',
            },
          ]),
          { status: 200, headers: { 'content-type': 'application/json' } },
        );
      }
      return new Response('quota exceeded', { status: 429 });
    }) as typeof fetch;

    const r = await run(['ping', '--project', '42'], { loadConfig: () => FAKE_CONFIG });
    expect(r.exitCode).toBe(1);
    expect(r.stderr).toContain('ingest rejected probe: HTTP 429');
  });
});
