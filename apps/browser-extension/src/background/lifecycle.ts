/**
 * `chrome.runtime.onInstalled` handler — runs once per install / update / browser_update.
 *
 * Responsibilities:
 * - Log the install/update event so the operator can correlate a regression with the
 *   version transition (visible in chrome://extensions → "Inspect views: service
 *   worker" → console).
 * - Trigger storage migrations. Each store wires its own migrations through the
 *   `readVersioned` helper at read time, so this handler only has to *touch* each
 *   store key — the helper does the work and persists the upgraded blob back.
 * - Skip everything that requires a user gesture (sidePanel.open, opening a welcome
 *   tab) — onInstalled fires from the service worker without a gesture, so those
 *   calls would silently fail. The operator-facing "first run" UX is the existing
 *   `startPath` redirect in SidepanelApp.tsx.
 */

import { getCapabilitySnapshot } from '../shared/storage/capability-cache';
import { getExecutionHistory } from '../shared/storage/execution-history';
import { getSettings } from '../shared/storage/settings-store';
import { getPageContext, getWorkspaceSelection } from '../shared/storage/workspace-store';

export interface LifecycleEvent {
  reason: chrome.runtime.OnInstalledReason | string;
  previousVersion?: string;
  currentVersion: string;
}

export async function runLifecycleEvent(event: LifecycleEvent): Promise<void> {
  // `console.warn` is the highest-noise tier ESLint lets us use in this codebase
  // (`no-console: ['error', { allow: ['warn', 'error'] }]`). Install/update is a one-
  // shot lifecycle event that's worth surfacing in the worker console for diagnostics —
  // it's not actually a warning, but the channel is the right one for a sysop-visible
  // event marker.
  console.warn(
    `[arguslog] runtime.onInstalled — reason=${event.reason} ${
      event.previousVersion ? `prev=${event.previousVersion} ` : ''
    }current=${event.currentVersion}`,
  );

  if (event.reason === 'install' || event.reason === 'update') {
    await runStorageMigrations();
  }
}

/**
 * Touch each versioned store so its `readVersioned` helper has a chance to detect
 * stale envelopes and lift them to the current schema version. The helper is idempotent —
 * a store already at the current version is a no-op read, ~1 ms each.
 *
 * Failures are logged and swallowed. A botched migration falls back to defaults at the
 * helper level, so the worst case is "operator's saved state reverted on update" — not
 * "extension crashes on every page load until manually reinstalled."
 */
async function runStorageMigrations(): Promise<void> {
  const stores: Array<[string, () => Promise<unknown>]> = [
    ['settings', getSettings],
    ['workspace-selection', getWorkspaceSelection],
    ['page-context', getPageContext],
    ['execution-history', getExecutionHistory],
    ['capability-snapshot', getCapabilitySnapshot],
  ];

  for (const [name, read] of stores) {
    try {
      await read();
    } catch (err) {
      console.warn(`[arguslog] migration touch failed for ${name}:`, err);
    }
  }
}
