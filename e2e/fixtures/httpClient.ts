/**
 * Tiny PAT-authenticated fetch wrapper used by the test-data fixtures.
 *
 * We deliberately avoid the project's web app `api/` module because that's bundled
 * for the browser (depends on the OIDC token in localStorage). The runner PAT lives
 * in env at test time and never touches the page — using `fetch` directly keeps the
 * surface small and avoids dragging the dashboard's runtime config helpers into
 * Node-side test infrastructure.
 */
import { e2eConfig } from '../playwright.config.js';

interface RequestOptions {
  method?: 'GET' | 'POST' | 'PATCH' | 'DELETE' | 'PUT';
  body?: unknown;
  /** Override the default runner-PAT auth (e.g. to call /api/v1/me with a per-test user token). */
  authToken?: string;
  query?: Record<string, string | number | undefined>;
}

function buildUrl(path: string, query?: RequestOptions['query']): string {
  const url = new URL(path.startsWith('http') ? path : `${e2eConfig.apiURL}${path}`);
  if (query) {
    for (const [k, v] of Object.entries(query)) {
      if (v !== undefined) url.searchParams.set(k, String(v));
    }
  }
  return url.toString();
}

export async function apiRequest<T = unknown>(path: string, opts: RequestOptions = {}): Promise<T> {
  const auth = opts.authToken ?? e2eConfig.runnerPAT;
  if (!auth) {
    throw new Error(
      `apiRequest: no auth token. Either pass opts.authToken or set ARGUSLOG_E2E_RUNNER_PAT. ` +
        `Target: ${path}`,
    );
  }
  const url = buildUrl(path, opts.query);
  const resp = await fetch(url, {
    method: opts.method ?? 'GET',
    headers: {
      Authorization: `Bearer ${auth}`,
      'Content-Type': 'application/json',
      Accept: 'application/json',
    },
    body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined,
  });
  if (!resp.ok) {
    const text = await resp.text().catch(() => '');
    throw new Error(
      `${opts.method ?? 'GET'} ${path} → ${resp.status} ${resp.statusText}${
        text ? ` — ${text.slice(0, 500)}` : ''
      }`,
    );
  }
  // 204 No Content has empty body — return undefined cast.
  if (resp.status === 204) return undefined as T;
  const contentType = resp.headers.get('content-type') ?? '';
  if (contentType.includes('application/json')) {
    return (await resp.json()) as T;
  }
  return (await resp.text()) as unknown as T;
}
