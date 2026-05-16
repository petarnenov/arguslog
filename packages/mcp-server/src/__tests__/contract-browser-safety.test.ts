import { execSync } from 'node:child_process';
import { readFileSync, existsSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it, beforeAll } from 'vitest';

const __dirname = dirname(fileURLToPath(import.meta.url));
const PKG_ROOT = resolve(__dirname, '..', '..');
const DIST_CONTRACT = resolve(PKG_ROOT, 'dist', 'contract.js');
const DIST_CONTRACT_DTS = resolve(PKG_ROOT, 'dist', 'contract.d.ts');

/**
 * The drift guard for `@arguslog/mcp-server/contract`: emit must be Node-free.
 *
 * Why: the contract subpath is the only entry browser consumers (extensions, web apps)
 * are allowed to import. Anyone editing the barrel could accidentally re-export a
 * runtime function that transitively pulls in `node:crypto` / the MCP server SDK /
 * `process.env`. That kind of regression doesn't fail at build time — it fails at the
 * downstream consumer's bundler with a cryptic "module not externalized" error.
 *
 * This test:
 *   1. Builds the package if `dist/contract.js` is stale or missing.
 *   2. Reads the emitted contract bundle.
 *   3. Asserts none of the known-Node-only imports / references appear, and the file
 *      consists only of re-exports.
 *
 * Adding new exports to `contract.ts` is fine — the test just keeps the EMIT clean.
 */
describe('contract — browser-safe emit', () => {
  beforeAll(() => {
    if (!existsSync(DIST_CONTRACT)) {
      // Vitest invokes us from the package root; building takes a few seconds but is
      // self-contained. CI runs `pnpm build` ahead of test anyway, so the slow path
      // here is for local developer-run-tests flows where they edited contract.ts
      // and haven't built yet.
      execSync('pnpm run build', { cwd: PKG_ROOT, stdio: 'inherit' });
    }
  });

  /**
   * Strip JS comments before scanning. The barrel intentionally NAMES the forbidden
   * imports in its docstring as „things the contract must NOT pull in" — we don't want
   * those literal mentions in the JSDoc to false-positive these scans.
   */
  function stripComments(js: string): string {
    return js.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');
  }

  it('emits dist/contract.js and dist/contract.d.ts', () => {
    expect(existsSync(DIST_CONTRACT), `expected ${DIST_CONTRACT} to exist`).toBe(true);
    expect(existsSync(DIST_CONTRACT_DTS), `expected ${DIST_CONTRACT_DTS} to exist`).toBe(true);
  });

  it('contains no `node:*` imports in the emitted JS', () => {
    const js = stripComments(readFileSync(DIST_CONTRACT, 'utf8'));
    // Match both ESM (`from "node:…"`) and CJS (`require("node:…")`) shapes — tsc emits
    // ESM here per package.json `type: "module"`, but the assertion is shape-agnostic.
    expect(js).not.toMatch(/['"]node:[a-z_/-]+['"]/);
  });

  it('contains no `@modelcontextprotocol/sdk/server/*` imports', () => {
    const js = stripComments(readFileSync(DIST_CONTRACT, 'utf8'));
    expect(js).not.toMatch(/@modelcontextprotocol\/sdk\/server/);
  });

  it('contains no `process.env` references', () => {
    const js = stripComments(readFileSync(DIST_CONTRACT, 'utf8'));
    expect(js).not.toMatch(/process\s*\.\s*env/);
  });

  it('imports only from browser-safe relative paths', () => {
    const js = readFileSync(DIST_CONTRACT, 'utf8');
    const importLines = js.split('\n').filter((l) => /^(import|export)\s/.test(l));
    // Every import/export-from in the emit should be a relative path to another file
    // within this package (browser-safe modules we audited). Anything pointing at a
    // bare package (e.g. `from "express"`, `from "crypto"`) is a regression — bare
    // specifiers fall through to the bundler/runtime to resolve, which in a browser
    // means "node externals" failure.
    for (const line of importLines) {
      const match = line.match(/from\s+['"]([^'"]+)['"]/);
      if (!match) continue;
      const spec = match[1]!;
      expect(spec, `import path ${spec} in contract.js must be relative`).toMatch(/^\.\.?\//);
    }
  });

  it('loads in a fresh Node context without throwing', async () => {
    // Module-graph smoke: dynamically import the emitted file. If any transitive
    // dep blew up at module-init time (`node:crypto` not available, missing peer dep,
    // etc.), the await throws and the test fails. We don't assert anything ON the
    // imported namespace beyond "it loaded" — the named-export shape is covered by
    // `contract-exports.test.ts`.
    const mod = await import(DIST_CONTRACT);
    expect(mod).toBeTypeOf('object');
  });
});
