/**
 * Bundle-size ratchet for the production build. Lives next to the unit tests (vitest
 * picks it up automatically) instead of e2e — it doesn't need a real browser, just
 * `fs.stat` on the build output.
 *
 * Three budgets:
 *
 * 1. **Cold-start chunk** — the sidepanel entry chunk loaded when the operator first
 *    opens the side panel. After D3 (route-level lazy loading) this is just the shell:
 *    MemoryRouter + connection-status query + sidebar nav. Budget: 50 kB. Growth past
 *    that means we accidentally static-imported a screen back into the entry.
 *
 * 2. **Background service worker** — single chunk loaded for every MCP round-trip.
 *    Budget: 320 kB. The MCP SDK is the bulk; growth past budget means we either added
 *    a heavy dep to the worker path or accidentally bundled UI code into it.
 *
 * 3. **Total bundle** — generous upper bound to catch dependency bloat. Budget:
 *    1 000 kB. Web Store accepts up to 50 MB but reviewers note bundle size in the
 *    rejection-reason history; staying under 1 MB total keeps us in the "instant
 *    review" bucket.
 *
 * When growth is justified (new feature, can't be lazy-loaded), edit the relevant
 * `_BUDGET_KB` constant in the same commit that introduces the growth. Reviewer should
 * see the budget bump and challenge it; that's the social ratchet.
 *
 * The test is `skipIf(!buildExists)` because the build artifact lives outside the
 * vitest sandbox in development. CI runs `pnpm build` before `pnpm test` so the file
 * is always present on the CI critical path; locally a dev who hasn't built yet sees a
 * single skipped test rather than a confusing FS error.
 */
import { existsSync, readdirSync, statSync } from 'node:fs';
import path from 'node:path';

import { describe, expect, it } from 'vitest';

const OUTPUT_DIR = path.resolve(__dirname, '../../.output/chrome-mv3');
const CHUNKS_DIR = path.join(OUTPUT_DIR, 'chunks');

const SIDEPANEL_ENTRY_BUDGET_KB = 50;
const BACKGROUND_BUDGET_KB = 320;
const TOTAL_BUDGET_KB = 1_000;

const buildExists = existsSync(path.join(OUTPUT_DIR, 'manifest.json'));

function sizeKb(filePath: string): number {
  return statSync(filePath).size / 1024;
}

/** Find the hashed chunk file matching a stable prefix (e.g. `sidepanel-` or `background`). */
function findChunkByPrefix(dir: string, prefix: string): string | null {
  if (!existsSync(dir)) return null;
  const match = readdirSync(dir).find((name) => name.startsWith(prefix));
  return match ? path.join(dir, match) : null;
}

function totalDirSize(dir: string): number {
  if (!existsSync(dir)) return 0;
  let bytes = 0;
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    bytes += entry.isDirectory() ? totalDirSize(full) : statSync(full).size;
  }
  return bytes;
}

describe.skipIf(!buildExists)('bundle-size ratchet', () => {
  it(`sidepanel entry chunk stays below ${SIDEPANEL_ENTRY_BUDGET_KB} kB`, () => {
    const chunk = findChunkByPrefix(CHUNKS_DIR, 'sidepanel-');
    expect(chunk).not.toBeNull();
    const kb = sizeKb(chunk as string);
    expect(kb, `${chunk}: ${kb.toFixed(1)} kB`).toBeLessThan(SIDEPANEL_ENTRY_BUDGET_KB);
  });

  it(`background.js stays below ${BACKGROUND_BUDGET_KB} kB`, () => {
    const file = path.join(OUTPUT_DIR, 'background.js');
    expect(existsSync(file), `${file} not found`).toBe(true);
    const kb = sizeKb(file);
    expect(kb, `${file}: ${kb.toFixed(1)} kB`).toBeLessThan(BACKGROUND_BUDGET_KB);
  });

  it(`total .output size stays below ${TOTAL_BUDGET_KB} kB`, () => {
    const kb = totalDirSize(OUTPUT_DIR) / 1024;
    expect(kb, `total: ${kb.toFixed(1)} kB`).toBeLessThan(TOTAL_BUDGET_KB);
  });
});
