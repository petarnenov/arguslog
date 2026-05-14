import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

import { PACKAGE_NAME, PACKAGE_VERSION } from '../generated/version.js';

const __dirname = dirname(fileURLToPath(import.meta.url));
const PKG = JSON.parse(readFileSync(resolve(__dirname, '../../package.json'), 'utf8')) as {
  name: string;
  version: string;
};

/**
 * Guards against the exact failure mode that [[feedback_mcp_version_single_source]] documents:
 * package.json gets bumped, the codegen step isn't re-run, and the wire reports a stale
 * PACKAGE_VERSION. Forcing the assert into the regular test suite means CI fails BEFORE
 * publish — the npm 0.5.0 / runtime 0.4.2 incident on 2026-05-11 was exactly this drift.
 */
describe('version drift guard', () => {
  it('generated PACKAGE_NAME matches package.json::name', () => {
    expect(PACKAGE_NAME).toBe(PKG.name);
  });

  it('generated PACKAGE_VERSION matches package.json::version', () => {
    // If this fails: run `pnpm --filter @arguslog/mcp-server run generate` to refresh
    // src/generated/version.ts, then commit the regenerated file.
    expect(PACKAGE_VERSION).toBe(PKG.version);
  });
});
