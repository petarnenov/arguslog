import type { ArguslogClient, Level } from '@arguslog/sdk-core';

/**
 * Patches {@code window.fetch} so every request leaves a breadcrumb. Records method + URL
 * pre-flight, then on resolution records the status (and duration). The original response is
 * passed through untouched.
 *
 * <p>Failures are recorded with {@code level: 'error'} but the rejection is re-thrown so user
 * code's catch blocks still fire. URLs are recorded as-is; if the user's DSN-aware proxy
 * scrubs query strings, that's handled at the scrubber layer, not here.
 */
export function installFetchBreadcrumbs(client: ArguslogClient): () => void {
  if (typeof window === 'undefined' || typeof window.fetch !== 'function') return () => {};

  // Keep the function reference unbound — uninstall must hand the same object back to
  // window.fetch, otherwise tests that compare references break and downstream patches that
  // expect an unmodified prototype get confused.
  const original = window.fetch;
  window.fetch = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const start = Date.now();
    const method = (init?.method ?? 'GET').toUpperCase();
    const url = requestUrl(input);
    try {
      const response = await Reflect.apply(original, window, [input, init]);
      try {
        const level: Level =
          response.status >= 500 ? 'error' : response.status >= 400 ? 'warning' : 'info';
        client.addBreadcrumb({
          category: 'fetch',
          message: `${method} ${url} → ${response.status}`,
          level,
          data: { method, url, status: response.status, durationMs: Date.now() - start },
        });
      } catch {
        // best-effort
      }
      return response;
    } catch (err) {
      try {
        client.addBreadcrumb({
          category: 'fetch',
          message: `${method} ${url} — network error`,
          level: 'error',
          data: { method, url, error: errorMessage(err), durationMs: Date.now() - start },
        });
      } catch {
        // best-effort
      }
      throw err;
    }
  };

  return () => {
    if (window.fetch !== original) window.fetch = original;
  };
}

function requestUrl(input: RequestInfo | URL): string {
  if (typeof input === 'string') return input;
  if (input instanceof URL) return input.toString();
  return input.url;
}

function errorMessage(err: unknown): string {
  if (err instanceof Error) return err.message;
  return String(err);
}
