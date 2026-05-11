import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { createOrg, deleteOrg, listMyOrgs } from '../../api/orgs';
import { archiveProject, createProject, listProjects } from '../../api/projects';
import { createRelease, deleteRelease, listReleases } from '../../api/releases';

const originalFetch = globalThis.fetch;
function mockFetch(body: unknown = {}): ReturnType<typeof vi.fn> {
  const f = vi.fn(async () => new Response(JSON.stringify(body), { status: 200 }));
  globalThis.fetch = f as typeof fetch;
  return f;
}

describe('orgs api client', () => {
  beforeEach(() => {
    globalThis.fetch = originalFetch;
  });
  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  it('listMyOrgs GETs /api/v1/orgs', async () => {
    const f = mockFetch([]);
    await listMyOrgs();
    expect(f.mock.calls[0]?.[0]).toContain('/api/v1/orgs');
  });

  it('createOrg POSTs the name', async () => {
    const f = mockFetch({});
    await createOrg('Acme Inc');
    expect(f.mock.calls[0]?.[0]).toContain('/api/v1/orgs');
    const init = f.mock.calls[0]?.[1] as RequestInit;
    expect(JSON.parse(String(init.body))).toEqual({ name: 'Acme Inc' });
  });

  it('deleteOrg DELETEs the id', async () => {
    const f = mockFetch({});
    await deleteOrg(42);
    expect(f.mock.calls[0]?.[0]).toContain('/api/v1/orgs/42');
    expect((f.mock.calls[0]?.[1] as RequestInit).method).toBe('DELETE');
  });
});

describe('projects api client', () => {
  beforeEach(() => {
    globalThis.fetch = originalFetch;
  });
  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  it('listProjects GETs /orgs/{id}/projects', async () => {
    const f = mockFetch([]);
    await listProjects(42);
    expect(f.mock.calls[0]?.[0]).toContain('/api/v1/orgs/42/projects');
  });

  it('createProject POSTs the body and returns the atomic {project, dsn} envelope', async () => {
    const f = mockFetch({ project: { id: 1 }, dsn: { dsn: 'arguslog://x' } });
    await createProject(42, { name: 'web', platform: 'react' });
    const init = f.mock.calls[0]?.[1] as RequestInit;
    expect(init.method).toBe('POST');
    expect(JSON.parse(String(init.body))).toEqual({ name: 'web', platform: 'react' });
  });

  it('archiveProject DELETEs', async () => {
    const f = mockFetch({});
    await archiveProject(42, 9);
    expect(f.mock.calls[0]?.[0]).toContain('/api/v1/orgs/42/projects/9');
    expect((f.mock.calls[0]?.[1] as RequestInit).method).toBe('DELETE');
  });
});

describe('releases api client', () => {
  beforeEach(() => {
    globalThis.fetch = originalFetch;
  });
  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  it('listReleases GETs /projects/{id}/releases', async () => {
    const f = mockFetch([]);
    await listReleases(9);
    expect(f.mock.calls[0]?.[0]).toContain('/api/v1/projects/9/releases');
  });

  it('createRelease POSTs the body', async () => {
    const f = mockFetch({});
    await createRelease(9, '1.2.3');
    expect((f.mock.calls[0]?.[1] as RequestInit).method).toBe('POST');
  });

  it('deleteRelease DELETEs', async () => {
    const f = mockFetch({});
    await deleteRelease(9, 7);
    expect((f.mock.calls[0]?.[1] as RequestInit).method).toBe('DELETE');
  });
});
