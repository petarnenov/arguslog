// Edge-runtime variant of `./server`. Resolved when bundlers honour the
// `edge-light` / `workerd` conditions (Next.js Edge runtime does). Same
// export shape as `./server.ts`, but every implementation is a no-op so
// the module graph never reaches @arguslog/sdk-node (which touches
// node:http / node:https and breaks Edge builds).
//
// Callers that need real behaviour gate on `process.env.NEXT_RUNTIME ===
// 'nodejs'` — those calls resolve to the Node variant via the package
// `exports` map, so no behaviour is lost.

// Types are imported from sdk-node — `import type` is erased at compile time, so
// no runtime reference to sdk-node (and its node:* imports) survives in the Edge bundle.
import type { Breadcrumb, Level, NodeArguslogOptions, User } from '@arguslog/sdk-node';

export type { Breadcrumb, EventPayload, Level, NodeArguslogOptions, User } from '@arguslog/sdk-node';

export interface ErrorContext {
  routerKind: 'Pages Router' | 'App Router';
  routePath: string;
  routeType: 'render' | 'route' | 'action' | 'middleware';
  renderSource?: 'react-server-components' | 'react-server-components-payload' | 'server-rendering';
  revalidateReason?: 'on-demand' | 'stale' | undefined;
  renderType?: 'dynamic' | 'dynamic-resume';
}

export interface RequestInfo {
  path: string;
  method: string;
  headers: Record<string, string | string[] | undefined>;
}

export function init(_options: NodeArguslogOptions): undefined {
  return undefined;
}

export function captureException(_error: unknown, _hint?: unknown): undefined {
  return undefined;
}

export function captureMessage(_message: string, _level?: Level): undefined {
  return undefined;
}

export function setUser(_user: User | undefined): void {}
export function setTag(_key: string, _value: string): void {}
export function setContext(_name: string, _ctx: Record<string, unknown>): void {}
export function addBreadcrumb(_crumb: Omit<Breadcrumb, 'timestamp'> & { timestamp?: number }): void {}
export function flush(): Promise<void> {
  return Promise.resolve();
}
export function getClient(): undefined {
  return undefined;
}
export function runWithRequestScope<T>(fn: () => T): T {
  return fn();
}

export function onRequestError(_err: unknown, _request: RequestInfo, _context: ErrorContext): void {}

export function wrapApiHandler<TArgs extends unknown[], TResult>(
  handler: (...args: TArgs) => TResult | Promise<TResult>,
): (...args: TArgs) => Promise<TResult> {
  return async (...args: TArgs) => handler(...args);
}

export function wrapRouteHandler<TArgs extends unknown[], TResult>(
  handler: (...args: TArgs) => TResult | Promise<TResult>,
): (...args: TArgs) => Promise<TResult> {
  return async (...args: TArgs) => handler(...args);
}

export function wrapServerAction<TArgs extends unknown[], TResult>(
  action: (...args: TArgs) => TResult | Promise<TResult>,
): (...args: TArgs) => Promise<TResult> {
  return async (...args: TArgs) => action(...args);
}
