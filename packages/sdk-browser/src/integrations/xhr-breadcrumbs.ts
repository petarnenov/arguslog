import type { ArguslogClient, Level } from '@arguslog/sdk-core';

/**
 * Patches {@code XMLHttpRequest.prototype.open} + {@code send} so legacy XHR calls (jQuery
 * AJAX, axios with XHR adapter, hand-rolled XHR) leave breadcrumbs same as fetch.
 *
 * <p>Stashes method + url on the XHR instance via a non-enumerable symbol-keyed property so
 * the load/error handlers can read them back. Original arguments pass through untouched.
 */
const XHR_META = Symbol.for('arguslog.xhrMeta');

interface XhrMeta {
  method: string;
  url: string;
  start: number;
}

interface XhrWithMeta extends XMLHttpRequest {
  [XHR_META]?: XhrMeta;
}

export function installXhrBreadcrumbs(client: ArguslogClient): () => void {
  if (typeof XMLHttpRequest === 'undefined') return () => {};

  const proto = XMLHttpRequest.prototype;
  const originalOpen = proto.open;
  const originalSend = proto.send;

  proto.open = function patchedOpen(
    this: XhrWithMeta,
    method: string,
    url: string | URL,
    ...rest: unknown[]
  ): void {
    try {
      this[XHR_META] = {
        method: method.toUpperCase(),
        url: typeof url === 'string' ? url : url.toString(),
        start: 0,
      };
    } catch {
      // best-effort
    }
    return (originalOpen as (...args: unknown[]) => void).apply(this, [method, url, ...rest]);
  };

  proto.send = function patchedSend(this: XhrWithMeta, body?: Document | XMLHttpRequestBodyInit | null): void {
    const meta = this[XHR_META];
    if (meta) {
      meta.start = Date.now();
      const onComplete = () => {
        try {
          const level: Level =
            this.status >= 500 ? 'error' : this.status >= 400 ? 'warning' : 'info';
          client.addBreadcrumb({
            category: 'xhr',
            message: `${meta.method} ${meta.url} → ${this.status}`,
            level,
            data: {
              method: meta.method,
              url: meta.url,
              status: this.status,
              durationMs: Date.now() - meta.start,
            },
          });
        } catch {
          // best-effort
        }
      };
      const onError = () => {
        try {
          client.addBreadcrumb({
            category: 'xhr',
            message: `${meta.method} ${meta.url} — network error`,
            level: 'error',
            data: {
              method: meta.method,
              url: meta.url,
              durationMs: Date.now() - meta.start,
            },
          });
        } catch {
          // best-effort
        }
      };
      this.addEventListener('loadend', onComplete);
      this.addEventListener('error', onError);
    }
    return originalSend.apply(this, [body ?? null]);
  };

  return () => {
    proto.open = originalOpen;
    proto.send = originalSend;
  };
}
