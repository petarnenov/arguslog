import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { run } from '../cli.js';
import {
  composeBody,
  parseDestinationIds,
  parseLevels,
  parseTagValuesFlag,
  parseWindowShorthand,
} from '../commands/alerts.js';

const originalFetch = globalThis.fetch;

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

const FAKE_CONFIG = { apiBaseUrl: 'http://api', token: 'arglog_pat_x' };

function fullRule(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    id: 7,
    projectId: 101,
    name: 'errors-in-prod',
    conditions: {
      level: { in: ['error', 'fatal'] },
      firstSeenWindow: 'PT5M',
      occurrenceThreshold: 3,
    },
    actions: { destinationIds: [10] },
    throttleSeconds: 600,
    enabled: true,
    createdAt: '2026-05-14T12:00:00Z',
    ...overrides,
  };
}

describe('alerts flag parsing helpers', () => {
  it('parses minute / hour / day shorthand to ISO-8601', () => {
    expect(parseWindowShorthand('5m')).toBe('PT5M');
    expect(parseWindowShorthand('2h')).toBe('PT2H');
    expect(parseWindowShorthand('1d')).toBe('P1D');
  });

  it('passes through raw ISO durations untouched (uppercased)', () => {
    expect(parseWindowShorthand('PT30S')).toBe('PT30S');
    expect(parseWindowShorthand('p1dt12h')).toBe('P1DT12H');
  });

  it('rejects nonsense window strings', () => {
    expect(parseWindowShorthand('5min')).toBeNull();
    expect(parseWindowShorthand('')).toBeNull();
  });

  it('rejects unknown levels', () => {
    expect(parseLevels('error,fatal')).toEqual(['error', 'fatal']);
    expect(parseLevels('critical')).toBeNull();
  });

  it('parses comma-separated destination ids', () => {
    expect(parseDestinationIds(['1,2,3'])).toEqual([1, 2, 3]);
    expect(parseDestinationIds(['10'])).toEqual([10]);
    expect(parseDestinationIds(['bad'])).toBeNull();
  });

  it('tag-values split on comma/space/semicolon', () => {
    expect(parseTagValuesFlag('production, staging ; dev')).toEqual([
      'production',
      'staging ; dev',
    ]);
  });

  it('composeBody preserves base values when flags are absent', () => {
    const base = fullRule();
    const body = composeBody({
      base: base as unknown as Parameters<typeof composeBody>[0]['base'],
      name: 'renamed',
    });
    expect(body.name).toBe('renamed');
    expect(body.conditions.firstSeenWindow).toBe('PT5M'); // inherited
    expect(body.conditions.occurrenceThreshold).toBe(3);
    expect(body.actions.destinationIds).toEqual([10]);
    expect(body.throttleSeconds).toBe(600);
  });
});

describe('alerts create', () => {
  beforeEach(() => {
    globalThis.fetch = originalFetch;
  });
  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  it('POSTs the typed conditions built from --level / --window / --threshold / --destination', async () => {
    const captured: { url: string; init?: RequestInit }[] = [];
    globalThis.fetch = vi.fn(async (input, init) => {
      captured.push({ url: typeof input === 'string' ? input : (input as Request).url, init });
      return jsonResponse(fullRule({ id: 42, name: 'smoke' }), 201);
    }) as typeof fetch;

    const result = await run(
      [
        'alerts',
        'create',
        '--project',
        '101',
        '--name',
        'smoke',
        '--level',
        'error,fatal',
        '--window',
        '5m',
        '--threshold',
        '3',
        '--destination',
        '10,11',
        '--throttle',
        '600',
      ],
      { loadConfig: () => FAKE_CONFIG },
    );

    expect(result.exitCode).toBe(0);
    expect(result.stdout).toContain('rule #42 created: smoke');
    expect(captured).toHaveLength(1);
    expect(captured[0]!.url).toBe('http://api/api/v1/projects/101/alert-rules');
    const body = JSON.parse(captured[0]!.init!.body as string) as Record<string, unknown>;
    expect(body.name).toBe('smoke');
    expect(body.conditions).toEqual({
      level: { in: ['error', 'fatal'] },
      firstSeenWindow: 'PT5M',
      occurrenceThreshold: 3,
    });
    expect(body.actions).toEqual({ destinationIds: [10, 11] });
    expect(body.throttleSeconds).toBe(600);
    expect(body.enabled).toBe(true);
  });

  it('requires --destination so the rule has somewhere to fire', async () => {
    const result = await run(['alerts', 'create', '--project', '101', '--name', 'smoke'], {
      loadConfig: () => FAKE_CONFIG,
    });
    expect(result.exitCode).toBe(2);
    expect(result.stderr).toContain('--destination');
  });

  it('rejects unknown --level values without hitting the api', async () => {
    const spy = vi.fn(async () => jsonResponse({}));
    globalThis.fetch = spy as typeof fetch;
    const result = await run(
      [
        'alerts',
        'create',
        '--project',
        '101',
        '--name',
        'smoke',
        '--destination',
        '10',
        '--level',
        'critical',
      ],
      { loadConfig: () => FAKE_CONFIG },
    );
    expect(result.exitCode).toBe(2);
    expect(spy).not.toHaveBeenCalled();
  });

  it('rejects --tag-key without --tag-values', async () => {
    const result = await run(
      [
        'alerts',
        'create',
        '--project',
        '101',
        '--name',
        'smoke',
        '--destination',
        '10',
        '--tag-key',
        'env',
      ],
      { loadConfig: () => FAKE_CONFIG },
    );
    expect(result.exitCode).toBe(2);
    expect(result.stderr).toContain('--tag-key');
  });
});

describe('alerts update', () => {
  beforeEach(() => {
    globalThis.fetch = originalFetch;
  });
  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  it('GETs the current rule then PUTs the merged body so omitted flags keep their value', async () => {
    const captured: { url: string; init?: RequestInit }[] = [];
    globalThis.fetch = vi.fn(async (input, init) => {
      const url = typeof input === 'string' ? input : (input as Request).url;
      captured.push({ url, init });
      if ((init?.method ?? 'GET') === 'GET') return jsonResponse(fullRule());
      return jsonResponse(fullRule({ name: 'renamed' }));
    }) as typeof fetch;

    const result = await run(['alerts', 'update', '7', '--project', '101', '--name', 'renamed'], {
      loadConfig: () => FAKE_CONFIG,
    });

    expect(result.exitCode).toBe(0);
    const put = captured.find((c) => c.init?.method === 'PUT')!;
    const body = JSON.parse(put.init!.body as string) as Record<string, unknown>;
    expect(body.name).toBe('renamed');
    // Inherits everything else from the GET response.
    expect(body.throttleSeconds).toBe(600);
    expect((body.actions as { destinationIds: number[] }).destinationIds).toEqual([10]);
    expect((body.conditions as { firstSeenWindow?: string }).firstSeenWindow).toBe('PT5M');
  });
});

describe('alerts delete', () => {
  beforeEach(() => {
    globalThis.fetch = originalFetch;
  });
  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  it('requires --yes before issuing DELETE', async () => {
    const spy = vi.fn(async () => jsonResponse({}));
    globalThis.fetch = spy as typeof fetch;
    const result = await run(['alerts', 'delete', '7', '--project', '101'], {
      loadConfig: () => FAKE_CONFIG,
    });
    expect(result.exitCode).toBe(2);
    expect(spy).not.toHaveBeenCalled();
  });

  it('issues DELETE when --yes is present', async () => {
    const captured: { url: string; init?: RequestInit }[] = [];
    globalThis.fetch = vi.fn(async (input, init) => {
      captured.push({ url: typeof input === 'string' ? input : (input as Request).url, init });
      return new Response(null, { status: 204 });
    }) as typeof fetch;
    const result = await run(['alerts', 'delete', '7', '--project', '101', '--yes'], {
      loadConfig: () => FAKE_CONFIG,
    });
    expect(result.exitCode).toBe(0);
    expect(captured[0]!.init!.method).toBe('DELETE');
  });
});
