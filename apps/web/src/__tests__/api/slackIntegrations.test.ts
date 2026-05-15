import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  deleteSlackWorkspace,
  listSlackWorkspaces,
  setSlackDefaultProject,
  startSlackInstall,
} from '../../api/slackIntegrations';

const originalFetch = globalThis.fetch;
function mockFetch(body: unknown = {}): ReturnType<typeof vi.fn> {
  const f = vi.fn(async () => new Response(JSON.stringify(body), { status: 200 }));
  globalThis.fetch = f as typeof fetch;
  return f;
}

describe('slackIntegrations api client', () => {
  beforeEach(() => {
    globalThis.fetch = originalFetch;
  });
  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  it('listSlackWorkspaces GETs /orgs/{id}/integrations/slack/workspaces', async () => {
    const f = mockFetch([]);
    await listSlackWorkspaces(42);
    expect(f.mock.calls[0]?.[0]).toContain('/api/v1/orgs/42/integrations/slack/workspaces');
  });

  it('deleteSlackWorkspace DELETEs by id', async () => {
    const f = mockFetch({});
    await deleteSlackWorkspace(42, 7);
    expect(f.mock.calls[0]?.[0]).toContain('/api/v1/orgs/42/integrations/slack/workspaces/7');
    expect((f.mock.calls[0]?.[1] as RequestInit).method).toBe('DELETE');
  });

  it('setSlackDefaultProject PATCHes with defaultProjectId', async () => {
    const f = mockFetch({});
    await setSlackDefaultProject(42, 7, 202);
    expect((f.mock.calls[0]?.[1] as RequestInit).method).toBe('PATCH');
    expect(JSON.parse(String((f.mock.calls[0]?.[1] as RequestInit).body))).toEqual({
      defaultProjectId: 202,
    });
  });

  it('setSlackDefaultProject sends null to clear the default', async () => {
    const f = mockFetch({});
    await setSlackDefaultProject(42, 7, null);
    expect(JSON.parse(String((f.mock.calls[0]?.[1] as RequestInit).body))).toEqual({
      defaultProjectId: null,
    });
  });

  it('startSlackInstall GETs the install endpoint and returns the authorize URL', async () => {
    const f = mockFetch({ authorizeUrl: 'https://slack.com/oauth/v2/authorize?state=xyz' });
    const result = await startSlackInstall(42);
    expect(f.mock.calls[0]?.[0]).toContain('/api/v1/orgs/42/integrations/slack/oauth/install');
    expect(result.authorizeUrl).toBe('https://slack.com/oauth/v2/authorize?state=xyz');
  });
});
