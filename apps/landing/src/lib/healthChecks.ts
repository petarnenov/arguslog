/**
 * Service health probes for the public status page. Pure JS — no React, no Mantine — so the
 * page renders a deterministic snapshot at any moment and tests can drive it without a DOM.
 *
 * Each {@link ServiceCheck} owns a single network call. Failures (CORS, network, non-2xx) all
 * collapse to {@code status: 'down'} so the page never gets stuck on a hanging fetch. Latency
 * is captured with {@code performance.now()} so we can show "responded in 142ms" without a
 * separate timing API.
 */

export type ServiceStatus = 'up' | 'down' | 'unknown';

export interface ServiceCheck {
  id: string;
  /** Human-facing label shown on the tile. */
  name: string;
  /** One-liner shown under the name. */
  description: string;
  /** URL the probe hits. */
  url: string;
}

export interface ProbeResult {
  id: string;
  status: ServiceStatus;
  /** ms from request start to response receive. {@code null} on network failure. */
  latencyMs: number | null;
  /** Timestamp the probe finished. */
  checkedAt: string;
  /** Free-text error description; only populated when status is "down". */
  error?: string;
}

/**
 * The 5 publicly-reachable Arguslog services. Worker has no public port (consumes Redis
 * Streams internally) — it's monitored by Railway healthchecks against its internal
 * /actuator/health, not from the browser. The status page calls out that limitation in the UI
 * copy rather than pretending the worker is unknown.
 */
export const SERVICES: ServiceCheck[] = [
  {
    id: 'api',
    name: 'API',
    description: 'Dashboard backend (orgs, projects, issues, members, MCP authority)',
    url: 'https://api.arguslog.org/actuator/health',
  },
  {
    id: 'ingest',
    name: 'Ingest',
    description: 'Public event endpoint — SDKs POST here',
    url: 'https://ingest.arguslog.org/actuator/health',
  },
  {
    id: 'mcp',
    name: 'MCP server',
    description: 'Model Context Protocol bridge for AI agents',
    url: 'https://mcp.arguslog.org/healthz',
  },
  {
    id: 'web',
    name: 'Dashboard',
    description: 'app.arguslog.org — where you sign in to triage issues',
    // Probes the Caddy /healthz route (open CORS) rather than the SPA root — fetching the
    // index.html document from a different origin is blocked by CORS even though Caddy returns
    // 200, so the page would render the dashboard as DOWN even when it's actually up.
    url: 'https://app.arguslog.org/healthz',
  },
  {
    id: 'landing',
    name: 'Marketing site',
    description: 'arguslog.org / www.arguslog.org — public pages + docs',
    url: 'https://www.arguslog.org/healthz',
  },
];

/**
 * Probe a single service. Resolves with a {@link ProbeResult}; never rejects (any thrown
 * error becomes {@code status: 'down'}). The 5s timeout caps the wait so a hung connection
 * doesn't stall the page indefinitely.
 */
export async function probeService(
  service: ServiceCheck,
  signal?: AbortSignal,
): Promise<ProbeResult> {
  const started = performance.now();
  const controller = new AbortController();
  const composite = mergeAbort(signal, controller.signal);
  const timeout = setTimeout(() => controller.abort(), 5000);

  try {
    const resp = await fetch(service.url, {
      method: 'GET',
      signal: composite,
      cache: 'no-store',
    });
    const latency = Math.round(performance.now() - started);
    if (!resp.ok) {
      return {
        id: service.id,
        status: 'down',
        latencyMs: latency,
        checkedAt: new Date().toISOString(),
        error: `HTTP ${resp.status}`,
      };
    }
    return {
      id: service.id,
      status: 'up',
      latencyMs: latency,
      checkedAt: new Date().toISOString(),
    };
  } catch (e) {
    return {
      id: service.id,
      status: 'down',
      latencyMs: null,
      checkedAt: new Date().toISOString(),
      error: e instanceof Error ? e.message : String(e),
    };
  } finally {
    clearTimeout(timeout);
  }
}

/** Probe every {@link SERVICES} entry in parallel. */
export function probeAll(signal?: AbortSignal): Promise<ProbeResult[]> {
  return Promise.all(SERVICES.map((s) => probeService(s, signal)));
}

/**
 * Rolls a per-service array up into a single overall state:
 *   - "operational" — every service up
 *   - "degraded"    — at least one but not all down
 *   - "outage"      — every service down
 *   - "unknown"     — empty / all probes returned unknown (initial render)
 */
export type OverallStatus = 'operational' | 'degraded' | 'outage' | 'unknown';

export function overallStatus(results: ProbeResult[]): OverallStatus {
  if (results.length === 0) return 'unknown';
  const ups = results.filter((r) => r.status === 'up').length;
  const downs = results.filter((r) => r.status === 'down').length;
  if (ups === results.length) return 'operational';
  if (downs === results.length) return 'outage';
  return 'degraded';
}

// AbortSignal.any exists in modern browsers but TypeScript lib targets vary; merge by hand.
function mergeAbort(a: AbortSignal | undefined, b: AbortSignal): AbortSignal {
  if (!a) return b;
  if (a.aborted) return a;
  if (b.aborted) return b;
  const out = new AbortController();
  a.addEventListener('abort', () => out.abort(a.reason));
  b.addEventListener('abort', () => out.abort(b.reason));
  return out.signal;
}
