import { buildSyntheticEvent } from '@arguslog/sdk-core';

import type { CliConfig } from '../config.js';
import { apiFetch } from '../http.js';

/**
 * Result of a connectivity probe — the eventId the ingest server echoed back, the public DSN
 * key the probe used (handy to confirm which key was active without leaking the secret), and
 * an exit hint for human consumers.
 */
export interface PingResult {
  eventId: string;
  dsnPublic: string;
  ingestUrl: string;
}

interface DsnSummary {
  id: number;
  projectId: number;
  dsnPublic: string;
  active: boolean;
  createdAt: string;
}

/** Server-side projection of a single DSN — exposed at list-time only, mirror of {@link DsnSummary}. */
type DsnListResponse = DsnSummary[];

/**
 * Sends one synthetic event through the full ingest pipeline using the project's first active
 * DSN. Mirrors what a real SDK would POST — same wire shape, same auth header — so a green
 * result is end-to-end confirmation that browser/server → ingest works for this project.
 *
 * Resolution order for the ingest URL:
 *   1. ARGUSLOG_INGEST_URL env override (set this for self-hosted)
 *   2. Derive from the api base URL by swapping the api subdomain → ingest
 *
 * The derive is heuristic but matches every deployment shape we ship — single-host monorepo
 * (api.arguslog.org → ingest.arguslog.org), self-host (api.acme.internal → ingest.acme.internal),
 * local dev (localhost:8081 → localhost:8080). Operators with a custom layout point to
 * ARGUSLOG_INGEST_URL explicitly.
 */
export async function ping(
  args: { projectId: number; ingestUrlOverride?: string },
  config: CliConfig,
): Promise<PingResult> {
  // 1. Get the project's first active DSN via the api (PAT-auth, projects:read).
  const dsns = await apiFetch<DsnListResponse>(config, `/api/v1/projects/${args.projectId}/keys`);
  const active = dsns.find((d) => d.active);
  if (!active) {
    throw new Error(
      `project ${args.projectId} has no active DSN — generate one in the dashboard or via 'arguslog dsn new' before pinging`,
    );
  }

  // 2. Resolve the ingest URL. Heuristic if no explicit override.
  const ingestUrl = args.ingestUrlOverride ?? deriveIngestUrl(config.apiBaseUrl);

  // 3. Build a synthetic event using the shared sdk-core builder so wire shape matches a real SDK.
  const payload = buildSyntheticEvent({ source: 'arguslog-cli ping' });

  // 4. POST to ingest with DSN auth (NOT the PAT — events are DSN-authed).
  const url = `${ingestUrl}/api/${args.projectId}/events`;
  const resp = await fetch(url, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-Arguslog-Auth': `Arguslog DSN ${active.dsnPublic}`,
    },
    body: JSON.stringify(payload),
  });
  if (!resp.ok) {
    const body = await resp.text().catch(() => '');
    throw new Error(`ingest rejected probe: HTTP ${resp.status}${body ? ` — ${body.slice(0, 200)}` : ''}`);
  }

  return { eventId: payload.eventId, dsnPublic: active.dsnPublic, ingestUrl };
}

export function deriveIngestUrl(apiBaseUrl: string): string {
  try {
    const u = new URL(apiBaseUrl);
    // Most deployments name-prefix the subdomain: api.example.com → ingest.example.com.
    // Local dev uses port swap: api on 8081, ingest on 8080.
    if (u.hostname.startsWith('api.')) {
      u.hostname = `ingest.${u.hostname.slice(4)}`;
      return `${u.protocol}//${u.host}`;
    }
    if (u.port === '8081') {
      u.port = '8080';
      return `${u.protocol}//${u.host}`;
    }
    // Fallback: assume same host (single-port shared deployment). The server-side routing
    // would have to handle path-based routing between api and ingest.
    return apiBaseUrl;
  } catch {
    return apiBaseUrl;
  }
}
