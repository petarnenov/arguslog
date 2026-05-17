import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { defineConfig } from '@playwright/test';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

/**
 * Playwright config for the browser-extension e2e suite.
 *
 * One project (`smoke`) that loads the unpacked production build from `.output/chrome-mv3/`
 * into a persistent Chromium context. Extensions can't run in Playwright's default headless
 * mode, so we use `headless: false` even in CI; xvfb-run is the standard wrapper if the
 * runner lacks a display.
 *
 * The `webServer` field is intentionally absent — there's nothing to serve. The test relies
 * on the build artifact already existing; `e2e` script depends on `build` so a clean
 * `pnpm --filter @arguslog/browser-extension e2e` invocation produces the prerequisites.
 */
export default defineConfig({
  testDir: path.resolve(__dirname, 'tests/e2e'),
  // Extensions are stateful; running tests in parallel against the same persistent context
  // would race over storage / sidepanel state. Serial keeps the smoke suite deterministic.
  workers: 1,
  reporter: process.env.CI ? 'github' : 'list',
  timeout: 30_000,
  expect: { timeout: 5_000 },
});
