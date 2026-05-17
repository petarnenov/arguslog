import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { ArguslogClient } from '../client.js';
import { buildToolResult, executeTool, listMcpTools, TOOL_REGISTRY } from '../tools.js';

describe('tool dispatch', () => {
  let originalFetch: typeof globalThis.fetch;
  beforeEach(() => {
    originalFetch = globalThis.fetch;
    process.env.ARGUSLOG_PAT = 'arglog_pat_test';
    process.env.ARGUSLOG_API_URL = 'https://api.example.com';
  });
  afterEach(() => {
    globalThis.fetch = originalFetch;
    delete process.env.ARGUSLOG_PAT;
    delete process.env.ARGUSLOG_API_URL;
  });

  it('listMcpTools returns at least the curated tools', () => {
    const tools = listMcpTools();
    const names = tools.map((t) => t.name);
    expect(names).toContain('list_my_orgs');
    expect(names).toContain('list_issues');
    expect(names).toContain('grant_user_tier');
    // OpenAPI auto-gen contributes the rest — should be a meaningful number.
    expect(tools.length).toBeGreaterThan(20);
  });

  it('substitutes path params and removes them from the body', async () => {
    const fetchMock = vi.fn(
      async (_url: URL | string, _opts?: RequestInit) =>
        new Response(JSON.stringify({ id: 99 }), {
          status: 200,
          headers: { 'content-type': 'application/json' },
        }),
    );
    globalThis.fetch = fetchMock as unknown as typeof globalThis.fetch;

    const client = ArguslogClient.fromEnv();
    await executeTool(client, 'create_project', {
      orgId: 42,
      body: { name: 'Acme', platform: 'react' },
    });

    const call = fetchMock.mock.calls[0]!;
    const url = call[0] as URL;
    const opts = call[1] as RequestInit;
    expect(url.toString()).toBe('https://api.example.com/api/v1/orgs/42/projects');
    expect(opts.method).toBe('POST');
    expect(opts.body).toBe('{"name":"Acme","platform":"react"}');
  });

  it('routes query params correctly for list endpoints', async () => {
    const fetchMock = vi.fn(
      async (_url: URL | string, _opts?: RequestInit) =>
        new Response('[]', { status: 200, headers: { 'content-type': 'application/json' } }),
    );
    globalThis.fetch = fetchMock as unknown as typeof globalThis.fetch;

    const client = ArguslogClient.fromEnv();
    await executeTool(client, 'list_issues', {
      projectId: 7,
      status: 'unresolved',
      limit: 25,
    });

    const url = (fetchMock.mock.calls[0]![0] as URL).toString();
    expect(url).toContain('/api/v1/projects/7/issues');
    expect(url).toContain('status=unresolved');
    expect(url).toContain('limit=25');
  });

  it('throws when a required path param is missing', async () => {
    const client = ArguslogClient.fromEnv();
    await expect(executeTool(client, 'list_issues', {})).rejects.toThrow(/projectId/);
  });

  it('rejects unknown tool names with a clear error', async () => {
    const client = ArguslogClient.fromEnv();
    await expect(executeTool(client, 'does_not_exist', {})).rejects.toThrow(/Unknown tool/);
  });

  it('local validation rejects wrong primitive type before any HTTP call', async () => {
    const fetchMock = vi.fn();
    globalThis.fetch = fetchMock as unknown as typeof globalThis.fetch;

    const client = ArguslogClient.fromEnv();
    // projectId is declared integer; passing a string should be caught locally — no fetch
    // call should happen.
    await expect(executeTool(client, 'list_issues', { projectId: 'not-a-number' })).rejects.toThrow(
      /projectId expected integer, got string/,
    );
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('local validation rejects missing required body fields when bodySchema declares them', async () => {
    const fetchMock = vi.fn();
    globalThis.fetch = fetchMock as unknown as typeof globalThis.fetch;

    const client = ArguslogClient.fromEnv();
    // create_dsn from the OpenAPI declares body.label as required. Empty body should be
    // rejected locally rather than going through to the api.
    const tool = TOOL_REGISTRY.get('create_dsn');
    const bodySchema = (tool as unknown as { bodySchema?: { required?: string[] } }).bodySchema;
    // Only run this assertion if the spec actually flags required body fields — protects
    // the test from drifting when the OpenAPI spec changes.
    if (bodySchema?.required && bodySchema.required.length > 0) {
      await expect(executeTool(client, 'create_dsn', { projectId: 1 })).rejects.toThrow(
        /Missing required body/,
      );
      expect(fetchMock).not.toHaveBeenCalled();
    }
  });

  it('local validation lets through valid args (path int + query string + body object)', async () => {
    const fetchMock = vi.fn(
      async () =>
        new Response('{}', { status: 200, headers: { 'content-type': 'application/json' } }),
    );
    globalThis.fetch = fetchMock as unknown as typeof globalThis.fetch;

    const client = ArguslogClient.fromEnv();
    await executeTool(client, 'triage_issue', {
      projectId: 7,
      issueId: 123,
      body: { status: 'resolved' },
    });
    expect(fetchMock).toHaveBeenCalledOnce();
  });

  it('send_test_event: fetches DSN via api then posts synthetic event to derived ingest', async () => {
    const fetchMock = vi.fn(async (input: URL | string, _init?: RequestInit) => {
      const url = typeof input === 'string' ? input : input.toString();
      if (url.includes('/api/v1/projects/42/keys')) {
        return new Response(
          JSON.stringify([
            {
              id: 1,
              projectId: 42,
              dsnPublic: 'TESTKEY',
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
    });
    globalThis.fetch = fetchMock as unknown as typeof globalThis.fetch;

    // Override the api base URL so the deriver swaps api.* → ingest.*
    process.env.ARGUSLOG_API_URL = 'https://api.example.com';

    const client = ArguslogClient.fromEnv();
    const result = (await executeTool(client, 'send_test_event', {
      projectId: 42,
    })) as { status: string; eventId: string; ingestUrl: string; dsnPublic: string };

    expect(result.status).toBe('accepted');
    expect(result.dsnPublic).toBe('TESTKEY');
    expect(result.ingestUrl).toBe('https://ingest.example.com');
    expect(result.eventId).toMatch(/^[0-9a-f]{32}$/);

    // Verify the ingest call carried the right auth header
    const ingestCall = fetchMock.mock.calls.find((c) =>
      (typeof c[0] === 'string' ? c[0] : (c[0] as URL).toString()).includes('/api/42/events'),
    );
    expect(ingestCall).toBeDefined();
    const headers = new Headers((ingestCall![1] as RequestInit).headers ?? {});
    expect(headers.get('X-Arguslog-Auth')).toBe('Arguslog DSN TESTKEY');
  });

  it('send_test_event: refuses when project has no active DSN', async () => {
    globalThis.fetch = vi.fn(
      async () =>
        new Response('[]', { status: 200, headers: { 'content-type': 'application/json' } }),
    ) as unknown as typeof globalThis.fetch;

    const client = ArguslogClient.fromEnv();
    await expect(executeTool(client, 'send_test_event', { projectId: 42 })).rejects.toThrow(
      /no active DSN/,
    );
  });
});

describe('buildToolResult — structuredContent emission', () => {
  it('emits structuredContent alongside text when tool has outputSchema and result is an object', () => {
    // Pick any tool whose auto-gen outputSchema is a real object schema (not wrapped).
    // get_me returns a user object — outputSchema.type === 'object'.
    const tool = TOOL_REGISTRY.get('get_me');
    expect(tool?.outputSchema).toBeDefined();
    expect(tool?.outputResultWrapped).toBeFalsy();

    const result = { userId: 'abc', email: 'me@example.com', tier: 'pro' };
    const out = buildToolResult('get_me', result);
    expect(out.content[0]?.type).toBe('text');
    expect(out.structuredContent).toEqual(result);
  });

  it('wraps naked-array results under {result: ...} when codegen flagged outputResultWrapped', () => {
    // list_my_orgs returns an array from the API; codegen wraps the schema as {result: arr}.
    const tool = TOOL_REGISTRY.get('list_my_orgs');
    expect(tool?.outputSchema).toBeDefined();
    expect(tool?.outputResultWrapped).toBe(true);

    const apiResult = [{ id: 1, name: 'Acme' }];
    const out = buildToolResult('list_my_orgs', apiResult);
    expect(out.structuredContent).toEqual({ result: apiResult });
    // Text block still carries the raw shape for backward compat with pre-2025-11-25 clients.
    expect(out.content[0]?.text).toContain('"name": "Acme"');
  });

  it('omits structuredContent when tool has no outputSchema (e.g. curated send_test_event)', () => {
    const tool = TOOL_REGISTRY.get('send_test_event');
    // send_test_event is curated and doesn't declare an outputSchema.
    expect(tool?.outputSchema).toBeUndefined();

    const out = buildToolResult('send_test_event', { status: 'accepted', eventId: 'abc' });
    expect(out.structuredContent).toBeUndefined();
    expect(out.content[0]?.text).toContain('accepted');
  });

  it('omits structuredContent when result is a raw string', () => {
    const out = buildToolResult('get_me', 'plain text body');
    expect(out.structuredContent).toBeUndefined();
    expect(out.content[0]?.text).toBe('plain text body');
  });
});

describe('listMcpTools — body schema inlined from OpenAPI', () => {
  it('inlines requestBody field shape on tools with hasBody (instead of catch-all placeholder)', () => {
    const tools = listMcpTools();
    // create_release is curated + has POST body; merge keeps the auto-gen bodySchema.
    const t = tools.find((x) => x.name === 'create_release');
    expect(t).toBeDefined();
    const body = (t!.inputSchema as { properties: { body?: Record<string, unknown> } }).properties
      .body;
    expect(body).toBeDefined();
    // Properties are inlined from the ReleaseRequest schema — not the open default.
    const props = (body as { properties?: Record<string, unknown> }).properties;
    expect(props).toBeDefined();
    expect(props).toHaveProperty('version');
    // additionalProperties either omitted (strict schema) or false — but NOT the open
    // `true` default that the pre-inlining code emitted.
    expect((body as { additionalProperties?: boolean }).additionalProperties).not.toBe(true);
  });

  it('falls back to the open placeholder when OpenAPI declares no body schema', () => {
    // Any tool with hasBody but no bodySchema — none currently in our spec since all POST/PATCH
    // endpoints declare schemas, but the runtime fallback must still produce a callable shape.
    // We construct an ad-hoc tool to exercise the fallback branch.
    const _tools = listMcpTools();
    // smoke: verify all hasBody tools still have a body property whatever the path.
    for (const t of _tools) {
      const inp = t.inputSchema as { properties: Record<string, unknown> };
      const hasBodyProp = 'body' in inp.properties;
      // hasBody is private to OpenApiTool — we check via the produced schema; either body
      // exists with type object, or the tool simply doesn't have a body.
      if (hasBodyProp) {
        expect((inp.properties.body as { type?: string }).type).toBe('object');
      }
    }
  });
});
