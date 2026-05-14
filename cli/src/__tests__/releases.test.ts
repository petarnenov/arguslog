import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { run } from '../cli.js';

const originalFetch = globalThis.fetch;

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

const FAKE_CONFIG = { apiBaseUrl: 'http://api', token: 'arglog_pat_x' };

// Helper: a release row with every field non-null so update-merge logic has something to carry
// forward when the caller doesn't override a flag.
function fullRelease(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    id: 7,
    projectId: 101,
    version: '1.2.3',
    createdAt: '2026-05-14T12:00:00Z',
    releasedAt: '2026-05-14T10:00:00Z',
    gitSha: 'abc1234567',
    gitRef: 'main',
    deployStage: 'production',
    changelog: 'first cut',
    ...overrides,
  };
}

describe('releases new', () => {
  beforeEach(() => {
    globalThis.fetch = originalFetch;
  });
  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  it('POSTs the version with bearer auth and prints the new release id', async () => {
    const captured: { url: string; init?: RequestInit }[] = [];
    globalThis.fetch = vi.fn(async (input, init) => {
      captured.push({ url: typeof input === 'string' ? input : (input as Request).url, init });
      return jsonResponse(fullRelease({ version: '1.2.3' }), 201);
    }) as typeof fetch;

    const result = await run(['releases', 'new', '1.2.3', '--project', '101'], {
      loadConfig: () => FAKE_CONFIG,
    });

    expect(result.exitCode).toBe(0);
    expect(result.stdout).toContain('release #7 created: 1.2.3');
    expect(captured).toHaveLength(1);
    expect(captured[0]!.url).toBe('http://api/api/v1/projects/101/releases');
    const headers = new Headers(captured[0]!.init!.headers);
    expect(headers.get('Authorization')).toBe('Bearer arglog_pat_x');
    // Payload carries version plus null defaults for the metadata fields — the API treats null
    // as "no value", so the row lands with only the version set.
    expect(JSON.parse(captured[0]!.init!.body as string)).toEqual({
      version: '1.2.3',
      releasedAt: null,
      gitSha: null,
      gitRef: null,
      deployStage: null,
      changelog: null,
    });
  });

  it('forwards optional metadata flags into the POST body', async () => {
    const captured: { init?: RequestInit }[] = [];
    globalThis.fetch = vi.fn(async (_input, init) => {
      captured.push({ init });
      return jsonResponse(fullRelease(), 201);
    }) as typeof fetch;

    const result = await run(
      [
        'releases',
        'new',
        '1.2.3',
        '--project',
        '101',
        '--released-at',
        '2026-05-14T10:00:00Z',
        '--git-sha',
        'abc1234',
        '--git-ref',
        'main',
        '--deploy-stage',
        'production',
        '--changelog',
        'first cut',
      ],
      { loadConfig: () => FAKE_CONFIG },
    );

    expect(result.exitCode).toBe(0);
    expect(JSON.parse(captured[0]!.init!.body as string)).toEqual({
      version: '1.2.3',
      releasedAt: '2026-05-14T10:00:00Z',
      gitSha: 'abc1234',
      gitRef: 'main',
      deployStage: 'production',
      changelog: 'first cut',
    });
  });

  it('surfaces api 409 as a friendly error and exit 1', async () => {
    globalThis.fetch = vi.fn(
      async () =>
        new Response(
          JSON.stringify({ title: 'Duplicate release', status: 409, detail: 'exists' }),
          { status: 409, headers: { 'Content-Type': 'application/problem+json' } },
        ),
    ) as typeof fetch;

    const result = await run(['releases', 'new', '1.2.3', '--project', '101'], {
      loadConfig: () => FAKE_CONFIG,
    });
    expect(result.exitCode).toBe(1);
    expect(result.stderr).toContain('api 409');
    expect(result.stderr).toContain('exists');
  });
});

describe('releases list', () => {
  it('GETs and prints one line per release plus a total', async () => {
    globalThis.fetch = vi.fn(async () =>
      jsonResponse([
        fullRelease({ id: 8, version: '2.0.0', deployStage: 'staging' }),
        fullRelease({ id: 7, version: '1.2.3', gitSha: 'def4567', deployStage: null }),
      ]),
    ) as typeof fetch;

    const result = await run(['releases', 'list', '--project', '101'], {
      loadConfig: () => FAKE_CONFIG,
    });

    expect(result.exitCode).toBe(0);
    expect(result.stdout).toContain('#8');
    expect(result.stdout).toContain('#7');
    expect(result.stdout).toContain('[staging]');
    expect(result.stdout).toContain('2 release(s).');
  });

  it('prints a friendly empty state when the project has no releases', async () => {
    globalThis.fetch = vi.fn(async () => jsonResponse([])) as typeof fetch;
    const result = await run(['releases', 'list', '--project', '101'], {
      loadConfig: () => FAKE_CONFIG,
    });
    expect(result.stdout).toContain('no releases yet');
  });
});

describe('releases get', () => {
  it('GETs the release and pretty-prints every field', async () => {
    globalThis.fetch = vi.fn(async () => jsonResponse(fullRelease())) as typeof fetch;
    const result = await run(['releases', 'get', '7', '--project', '101'], {
      loadConfig: () => FAKE_CONFIG,
    });

    expect(result.exitCode).toBe(0);
    expect(result.stdout).toContain('id:           7');
    expect(result.stdout).toContain('gitSha:       abc1234567');
    expect(result.stdout).toContain('changelog:');
    expect(result.stdout).toContain('first cut');
  });
});

describe('releases update', () => {
  it('fetches current row, merges overrides, then PUTs the full payload', async () => {
    const captured: { url: string; init?: RequestInit }[] = [];
    globalThis.fetch = vi.fn(async (input, init) => {
      const url = typeof input === 'string' ? input : (input as Request).url;
      captured.push({ url, init });
      const method = (init?.method ?? 'GET').toUpperCase();
      if (method === 'GET') return jsonResponse(fullRelease());
      return jsonResponse(fullRelease({ deployStage: 'staging' }));
    }) as typeof fetch;

    const result = await run(
      ['releases', 'update', '7', '--project', '101', '--deploy-stage', 'staging'],
      { loadConfig: () => FAKE_CONFIG },
    );

    expect(result.exitCode).toBe(0);
    expect(captured).toHaveLength(2);
    expect(captured[0]!.init?.method ?? 'GET').toBe('GET');
    expect(captured[1]!.init!.method).toBe('PUT');
    // Body must carry every field — overridden deploy_stage plus carried-forward originals.
    expect(JSON.parse(captured[1]!.init!.body as string)).toEqual({
      version: '1.2.3',
      releasedAt: '2026-05-14T10:00:00Z',
      gitSha: 'abc1234567',
      gitRef: 'main',
      deployStage: 'staging',
      changelog: 'first cut',
    });
  });

  it('clears a field when the caller passes an empty string', async () => {
    const captured: { init?: RequestInit }[] = [];
    globalThis.fetch = vi.fn(async (_input, init) => {
      const method = (init?.method ?? 'GET').toUpperCase();
      if (method === 'GET') return jsonResponse(fullRelease());
      captured.push({ init });
      return jsonResponse(fullRelease({ changelog: null }));
    }) as typeof fetch;

    await run(['releases', 'update', '7', '--project', '101', '--changelog='], {
      loadConfig: () => FAKE_CONFIG,
    });
    expect(JSON.parse(captured[0]!.init!.body as string).changelog).toBeNull();
  });

  it('refuses to PUT if no editable flag was supplied', async () => {
    globalThis.fetch = vi.fn(async () => jsonResponse(fullRelease())) as typeof fetch;
    const result = await run(['releases', 'update', '7', '--project', '101'], {
      loadConfig: () => FAKE_CONFIG,
    });
    expect(result.exitCode).toBe(2);
    expect(result.stderr).toMatch(/at least one of/i);
  });
});

describe('releases delete', () => {
  it('refuses without --yes', async () => {
    globalThis.fetch = vi.fn(async () => new Response(null, { status: 204 })) as typeof fetch;
    const result = await run(['releases', 'delete', '7', '--project', '101'], {
      loadConfig: () => FAKE_CONFIG,
    });
    expect(result.exitCode).toBe(2);
    expect(result.stderr).toContain('--yes');
  });

  it('DELETEs and prints confirmation when --yes is set', async () => {
    const captured: { url: string; init?: RequestInit }[] = [];
    globalThis.fetch = vi.fn(async (input, init) => {
      captured.push({ url: typeof input === 'string' ? input : (input as Request).url, init });
      return new Response(null, { status: 204 });
    }) as typeof fetch;

    const result = await run(['releases', 'delete', '7', '--project', '101', '--yes'], {
      loadConfig: () => FAKE_CONFIG,
    });
    expect(result.exitCode).toBe(0);
    expect(result.stdout).toContain('release #7 deleted');
    expect(captured[0]!.init!.method).toBe('DELETE');
    expect(captured[0]!.url).toBe('http://api/api/v1/projects/101/releases/7');
  });
});
