import { ArgusClient } from './client.js';
import { installGlobalHandlers } from './integrations/global-handlers.js';
import type { ArgusOptions, Breadcrumb, Level, User } from './types.js';

export type { ArgusOptions, Breadcrumb, EventPayload, Level, StackFrame, User } from './types.js';
export { ArgusClient } from './client.js';
export { parseDsn, InvalidDsnError } from './dsn.js';

let currentClient: ArgusClient | undefined;
let uninstallGlobalHandlers: (() => void) | undefined;

export function init(options: ArgusOptions): ArgusClient {
  // Tear down a prior init's handlers before swapping the client — otherwise a hot-reload can
  // accumulate listeners that point at a stale ArgusClient.
  uninstallGlobalHandlers?.();
  uninstallGlobalHandlers = undefined;

  currentClient = new ArgusClient(options);

  if (options.integrations?.includes('globalHandlers')) {
    uninstallGlobalHandlers = installGlobalHandlers(currentClient);
  }

  return currentClient;
}

export function getClient(): ArgusClient | undefined {
  return currentClient;
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

/** Test-only: reset the singleton client between tests. */
export function __resetForTests(): void {
  uninstallGlobalHandlers?.();
  uninstallGlobalHandlers = undefined;
  currentClient = undefined;
}
