/**
 * Real-browser smoke test for the sidepanel.
 *
 * Why this exists: vitest can render React components and mock chrome.storage, but it
 * can't catch (a) manifest validation regressions (typos in wxt.config that produce a
 * broken `.output/chrome-mv3/manifest.json`), (b) MV3 service-worker registration
 * failures, (c) sidepanel HTML wiring breakages, or (d) Chrome's per-version side-panel
 * API drift. This Playwright suite loads the unpacked build into a real Chromium
 * instance and verifies the operator-visible surface.
 *
 * The suite is intentionally TINY — three assertions covering the regressions the
 * sidepanel has actually hit (Connect nav-link dedup, blank sidepanel on missing PAT,
 * service worker failing to register). Adding deeper integration tests goes elsewhere
 * (the unit suite remains the right place for tight feedback loops).
 *
 * Prereqs: the test runs against `.output/chrome-mv3/` produced by `pnpm build`. If the
 * dir is missing it fails fast with a clear message rather than a cryptic Playwright
 * stack — see {@link assertExtensionBuilt}.
 */
import { existsSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { chromium, expect, test, type BrowserContext, type Worker } from '@playwright/test';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const EXTENSION_PATH = path.resolve(__dirname, '../../.output/chrome-mv3');

function assertExtensionBuilt(): void {
  if (!existsSync(path.join(EXTENSION_PATH, 'manifest.json'))) {
    throw new Error(
      `Extension build not found at ${EXTENSION_PATH}. ` +
        `Run 'pnpm --filter @arguslog/browser-extension build' before 'pnpm e2e'.`,
    );
  }
}

/**
 * Launch persistent Chromium with the extension loaded. Chrome assigns the extension a
 * stable ID derived from the manifest's public key (or a random one if no key is set),
 * so we read it from the registered service-worker target instead of hard-coding it.
 */
async function launchWithExtension(): Promise<{ context: BrowserContext; extensionId: string }> {
  assertExtensionBuilt();
  const context = await chromium.launchPersistentContext('', {
    headless: false,
    args: [
      `--disable-extensions-except=${EXTENSION_PATH}`,
      `--load-extension=${EXTENSION_PATH}`,
      '--no-first-run',
      '--no-default-browser-check',
    ],
  });

  // MV3 service worker: wait for it (or take an already-registered one).
  const existing: Worker | undefined = context.serviceWorkers()[0];
  const serviceWorker: Worker = existing ?? (await context.waitForEvent('serviceworker'));
  const url = new URL(serviceWorker.url());
  const extensionId = url.hostname; // chrome-extension://<id>/...
  return { context, extensionId };
}

test('sidepanel.html renders the React app and mounts the sidebar', async () => {
  const { context, extensionId } = await launchWithExtension();
  try {
    const page = await context.newPage();
    await page.goto(`chrome-extension://${extensionId}/sidepanel.html`);

    // Wait for the root container to populate. The sidepanel app brands itself in the
    // sidebar header, which is the cheapest "did React mount" assertion that doesn't
    // depend on any per-screen content.
    // `exact: true` to disambiguate from the unrelated "Connect to Arguslog MCP" form
    // title rendered by the Connect screen below the sidebar.
    await expect(page.getByText('Arguslog MCP', { exact: true })).toBeVisible();
  } finally {
    await context.close();
  }
});

test('sidebar navigation does NOT contain a "Connect" item (regression: 4736026)', async () => {
  const { context, extensionId } = await launchWithExtension();
  try {
    const page = await context.newPage();
    await page.goto(`chrome-extension://${extensionId}/sidepanel.html`);
    // `exact: true` to disambiguate from the unrelated "Connect to Arguslog MCP" form
    // title rendered by the Connect screen below the sidebar.
    await expect(page.getByText('Arguslog MCP', { exact: true })).toBeVisible();

    // The Connect screen still exists as the onboarding landing for unauth'd operators,
    // but it must not appear as a sidebar NavLink — that was the duplicate-affordance
    // bug filed against the sidepanel. If it returns, this assertion catches it before
    // the next operator complaint does.
    const navItems = [
      'Workspace',
      'Issues',
      'Releases',
      'Workflows',
      'Tools',
      'History',
      'Playbooks',
      'Settings',
    ];
    for (const label of navItems) {
      await expect(page.getByRole('link', { name: label })).toBeVisible();
    }
    await expect(page.getByRole('link', { name: 'Connect' })).toHaveCount(0);
  } finally {
    await context.close();
  }
});

test('extension manifest is MV3 with only the documented permissions', async () => {
  const { context, extensionId } = await launchWithExtension();
  try {
    const page = await context.newPage();
    // The manifest is web-accessible by default for the extension's own pages.
    await page.goto(`chrome-extension://${extensionId}/manifest.json`);
    const text = await page.locator('body').innerText();
    const manifest = JSON.parse(text);

    expect(manifest.manifest_version).toBe(3);
    // Tightening 'tabs' → 'activeTab' was A1 of the MV3-best-practices sync; pin the
    // resulting permission set so a future "let's just add tabs back for convenience"
    // commit fails this assertion loudly.
    expect(new Set(manifest.permissions)).toEqual(
      new Set(['storage', 'activeTab', 'clipboardWrite', 'downloads', 'sidePanel']),
    );
    expect(manifest.permissions).not.toContain('tabs');
  } finally {
    await context.close();
  }
});
