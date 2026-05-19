import { describe, expect, it, vi } from 'vitest';

import * as edge from '../server.edge.js';

/**
 * server.edge.ts is the Edge-runtime variant of `./server` — every export is a no-op so
 * the bundle graph never reaches @arguslog/sdk-node (which imports node:http/node:https
 * and breaks Edge builds). These tests pin the shape:
 *
 *   - return-undefined functions truly return undefined,
 *   - void functions don't throw,
 *   - the wrap* helpers wrap their target without altering its result,
 *   - flush resolves immediately,
 *   - runWithRequestScope passes through the inner fn's return.
 *
 * Coverage purpose: server.edge.ts has no test coverage because the e2e suite runs in
 * Node mode (server.ts), but vitest still includes it in the package's coverage report
 * and drags the global threshold below 75%. Pinning the no-op semantics with cheap unit
 * tests fixes that without weakening the threshold.
 */
describe('sdk-nextjs/server.edge — no-op shims', () => {
  it('init / captureException / captureMessage / getClient return undefined', () => {
    expect(edge.init({} as never)).toBeUndefined();
    expect(edge.captureException(new Error('boom'))).toBeUndefined();
    expect(edge.captureException(new Error('boom'), { extra: 1 })).toBeUndefined();
    expect(edge.captureMessage('hello')).toBeUndefined();
    expect(edge.captureMessage('hello', 'warning')).toBeUndefined();
    expect(edge.getClient()).toBeUndefined();
  });

  it('void mutators (setUser / setTag / setContext / addBreadcrumb / onRequestError) do not throw', () => {
    expect(() => edge.setUser(undefined)).not.toThrow();
    expect(() => edge.setUser({ id: 'u1' } as never)).not.toThrow();
    expect(() => edge.setTag('env', 'edge')).not.toThrow();
    expect(() => edge.setContext('runtime', { name: 'edge' })).not.toThrow();
    expect(() => edge.addBreadcrumb({ message: 'click' } as never)).not.toThrow();
    expect(() =>
      edge.onRequestError(
        new Error('x'),
        { path: '/x', method: 'GET', headers: {} },
        { routerKind: 'App Router', routePath: '/x', routeType: 'route' },
      ),
    ).not.toThrow();
  });

  it('flush resolves immediately', async () => {
    await expect(edge.flush()).resolves.toBeUndefined();
  });

  it('runWithRequestScope returns the inner fn result unchanged', () => {
    expect(edge.runWithRequestScope(() => 42)).toBe(42);
    expect(edge.runWithRequestScope(() => 'edge')).toBe('edge');
  });

  // The three wrap* helpers all return an async function that invokes the original.
  // Test them parameterised so a future fourth wrapper picks up the same shape check.
  for (const [name, wrap] of [
    ['wrapApiHandler', edge.wrapApiHandler],
    ['wrapRouteHandler', edge.wrapRouteHandler],
    ['wrapServerAction', edge.wrapServerAction],
  ] as const) {
    describe(`${name}`, () => {
      it('calls through with the same args and returns the same value', async () => {
        const inner = vi.fn((a: number, b: number) => a + b);
        const wrapped = wrap(inner);
        await expect(wrapped(2, 3)).resolves.toBe(5);
        expect(inner).toHaveBeenCalledExactlyOnceWith(2, 3);
      });

      it('awaits async inner functions and forwards rejection', async () => {
        const wrapped = wrap(async () => {
          throw new Error('inner-failed');
        });
        await expect(wrapped()).rejects.toThrow('inner-failed');
      });
    });
  }
});
