/**
 * Per-test data isolation fixtures. Every authenticated test that needs an org or
 * project receives a freshly-created one named with the GitHub run id (or a local
 * timestamp) plus a short hash, so parallel runs never collide. Teardown deletes
 * the org via `DELETE /api/v1/orgs/{id}` — cascades to projects, DSNs, alert rules,
 * releases, members (everything scoped under the org).
 *
 * Cleanup runs on test failure too so staging doesn't accumulate `e2e-*` shells.
 * If a test exits via a Playwright timeout (the worker is killed mid-teardown),
 * the orphan org gets swept by the nightly cleanup script — see
 * `e2e/scripts/cleanup-orphans.ts` (which the cron job in `e2e-staging.yml` runs
 * before each suite).
 */
import { randomBytes } from 'node:crypto';

import { e2eConfig } from '../playwright.config.js';

import { apiRequest } from './httpClient.js';

export interface SeededOrg {
  id: number;
  slug: string;
  name: string;
}

export interface SeededProject {
  id: number;
  slug: string;
  name: string;
  orgId: number;
  orgSlug: string;
}

export interface SeededDsn {
  id: number;
  dsn: string;
  publicKey: string;
}

function uniqueName(prefix: string): string {
  const shortRunId = e2eConfig.runId.slice(-6);
  const random = randomBytes(2).toString('hex'); // 4 hex chars
  return `${prefix}-${shortRunId}-${random}`;
}

export async function createOrg(name?: string): Promise<SeededOrg> {
  const orgName = name ?? uniqueName('e2e-org');
  // Naming aligns with the API contract — name is required, slug is server-derived.
  const created = await apiRequest<{ id: number; slug: string; name: string }>('/api/v1/orgs', {
    method: 'POST',
    body: { name: orgName },
  });
  return { id: created.id, slug: created.slug, name: created.name };
}

export async function createProject(
  org: { id: number; slug: string },
  opts: { name?: string; platform?: string } = {},
): Promise<SeededProject> {
  const name = opts.name ?? uniqueName('e2e-project');
  const platform = opts.platform ?? 'javascript';
  // POST /orgs/{orgId}/projects returns ProjectCreateResponse — { project, dsn } —
  // bundling the freshly-minted DSN. We only need the project fields here; the org
  // slug isn't on the wire (project carries orgId, not orgSlug) so we forward the
  // caller-supplied org.slug onto the returned SeededProject.
  const created = await apiRequest<{
    project: { id: number; slug: string; name: string; orgId: number };
  }>(`/api/v1/orgs/${org.id}/projects`, {
    method: 'POST',
    body: { name, platform },
  });
  return {
    id: created.project.id,
    slug: created.project.slug,
    name: created.project.name,
    orgId: created.project.orgId,
    orgSlug: org.slug,
  };
}

export async function createDsn(projectId: number): Promise<SeededDsn> {
  const created = await apiRequest<{ id: number; dsn: string; dsnPublic: string }>(
    `/api/v1/projects/${projectId}/keys`,
    { method: 'POST', body: {} },
  );
  return { id: created.id, dsn: created.dsn, publicKey: created.dsnPublic };
}

export async function deleteOrg(orgId: number): Promise<void> {
  try {
    await apiRequest(`/api/v1/orgs/${orgId}`, { method: 'DELETE' });
  } catch (err) {
    // Swallow 404s on teardown (the test may have deleted it explicitly).
    if (err instanceof Error && /404/.test(err.message)) return;
    // Log but never re-throw on teardown — a teardown failure shouldn't mask the
    // test result. The orphan-sweep cron picks up anything left behind.
    console.warn(`testData: deleteOrg(${orgId}) failed:`, err);
  }
}

/**
 * Posts a single synthetic event to the DSN's ingest endpoint so a project shows
 * up with an issue in the dashboard. Returns the event id the SDK would generate.
 */
export async function ingestSyntheticEvent(
  dsn: SeededDsn,
  hint: { message?: string } = {},
): Promise<string> {
  const eventId = randomBytes(16).toString('hex');
  const ingestUrl = `${e2eConfig.apiURL}/api/${extractProjectIdFromDsn(dsn.dsn)}/events`;
  const payload = {
    eventId,
    timestamp: Date.now(),
    level: 'error',
    platform: 'javascript',
    sdk: { name: 'e2e-fixture', version: '0.0.0' },
    exception: {
      values: [
        {
          type: 'E2EFixtureError',
          value: hint.message ?? `Synthetic event from E2E run ${e2eConfig.runId}`,
        },
      ],
    },
  };
  const resp = await fetch(ingestUrl, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-Arguslog-Auth': `Arguslog DSN ${dsn.publicKey}`,
    },
    body: JSON.stringify(payload),
  });
  if (!resp.ok) {
    throw new Error(`ingestSyntheticEvent failed: ${resp.status} ${resp.statusText}`);
  }
  return eventId;
}

function extractProjectIdFromDsn(dsn: string): string {
  // arguslog://<key>@<host>/api/<projectId>
  const match = dsn.match(/\/api\/(\d+)$/);
  if (!match) throw new Error(`Cannot extract projectId from DSN: ${dsn}`);
  return match[1]!;
}
