import { ArguslogClient } from '@arguslog/sdk-core';
import type { ArguslogOptions, Breadcrumb, Level, User } from '@arguslog/sdk-core';

import { NodeAdapter } from './adapter.js';
import {
  installProcessHandlers,
  type ProcessHandlerOptions,
} from './integrations/process-handlers.js';
import { AsyncLocalScopeStore } from './scope.js';
import { parseStack } from './stack-parser.js';

export type {
  ArguslogOptions,
  Breadcrumb,
  EventPayload,
  Level,
  ScopeStore,
  StackFrame,
  User,
} from '@arguslog/sdk-core';
export { ArguslogClient, GlobalScope, InvalidDsnError, parseDsn } from '@arguslog/sdk-core';
export type { ProcessHandlerOptions } from './integrations/process-handlers.js';
export { AsyncLocalScopeStore } from './scope.js';

export type NodeIntegration = 'processHandlers';

export interface NodeArguslogOptions extends Omit<ArguslogOptions, 'integrations'> {
  integrations?: NodeIntegration[];
  processHandlers?: ProcessHandlerOptions;
}

let currentClient: ArguslogClient | undefined;
let currentScopeStore: AsyncLocalScopeStore | undefined;
let uninstallProcessHandlers: (() => void) | undefined;

export function init(options: NodeArguslogOptions): ArguslogClient {
  // Tear down handlers from a prior init before swapping the client — otherwise process
  // listeners from the previous client linger and capture into a stale instance.
  uninstallProcessHandlers?.();
  uninstallProcessHandlers = undefined;

  const { integrations, processHandlers, ...coreOptions } = options;
  const scopeStore = new AsyncLocalScopeStore(options.maxBreadcrumbs ?? 50);
  currentScopeStore = scopeStore;
  currentClient = new ArguslogClient(coreOptions, {
    adapter: new NodeAdapter(),
    parseStack,
    scopeStore,
  });

  if (integrations?.includes('processHandlers')) {
    uninstallProcessHandlers = installProcessHandlers(currentClient, processHandlers);
  }

  return currentClient;
}

export function getClient(): ArguslogClient | undefined {
  return currentClient;
}

/** Internal — used by sub-path integrations (Express middleware, etc.) to share the SDK's ALS. */
export function getScopeStore(): AsyncLocalScopeStore | undefined {
  return currentScopeStore;
}

export function captureException(
  error: unknown,
  hint?: { level?: Level; tags?: Record<string, string> },
): string | undefined {
  return currentClient?.captureException(error, hint);
}

export function captureMessage(message: string, level?: Level): string | undefined {
  return currentClient?.captureMessage(message, level);
}

export function setUser(user: User | undefined): void {
  currentClient?.setUser(user);
}

export function setTag(key: string, value: string): void {
  currentClient?.setTag(key, value);
}

export function setContext(name: string, ctx: Record<string, unknown>): void {
  currentClient?.setContext(name, ctx);
}

export function addBreadcrumb(crumb: Omit<Breadcrumb, 'timestamp'> & { timestamp?: number }): void {
  currentClient?.addBreadcrumb(crumb);
}

export function flush(): Promise<void> {
  return currentClient?.flush() ?? Promise.resolve();
}

/**
 * Runs `fn` inside a fresh request-scoped scope, forked from the current/global one. Used by the
 * Express middleware; also available for hand-rolled instrumentation (e.g. queue workers, gRPC).
 */
export function runWithRequestScope<T>(fn: () => T): T {
  if (!currentScopeStore) return fn();
  const child = currentScopeStore.fork();
  return currentScopeStore.run(child, fn);
}

/** Test-only: reset the singleton client between tests. */
export function __resetForTests(): void {
  uninstallProcessHandlers?.();
  uninstallProcessHandlers = undefined;
  currentClient = undefined;
  currentScopeStore = undefined;
}
