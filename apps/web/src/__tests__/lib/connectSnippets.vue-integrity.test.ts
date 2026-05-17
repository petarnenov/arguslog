/**
 * Vue Connect snippet integrity (Phase B of arguslog-sdks#2).
 *
 * The "createArguslog vs arguslogPlugin" drift slipped through because nothing in CI
 * actually parsed the catalog snippet text or cross-checked the imported symbols against
 * what the SDK exports. This file closes that hole with two cheap mechanical checks:
 *
 *   1. Every TS file in `SDK_CATALOG['vue'].initFiles` (and the recommended-architecture
 *      extras) parses as valid TypeScript syntax. A typo or a stray ``` fence flagged
 *      by `ts.createSourceFile` would surface as a diagnostic.
 *
 *   2. Every symbol the snippet imports from `@arguslog/sdk-vue` is actually exported
 *      by the package. We import the SDK module live and assert the names are present
 *      at runtime — if someone renames `createArguslog` the test fails LOUD here, not
 *      silently in user copy-paste land.
 *
 * Light-weight by design: no `vue-tsc`, no temp project, no subprocess. The full type
 * check is a future enhancement; what we cover here catches the bug class the issue
 * reporter actually hit.
 */
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import ts from 'typescript';
import { describe, expect, it } from 'vitest';

import { SDK_CATALOG } from '../../lib/connectSnippets';

/**
 * `@arguslog/sdk-vue` is not a runtime dep of @arguslog/web (the dashboard never loads the
 * Vue SDK in-process). Instead of pulling it in just for this test, parse the SDK's source
 * index module and extract the export list — same effective check without bloating
 * package.json or test bundles.
 */
function readSdkVueExports(): Set<string> {
  const indexPath = resolve(__dirname, '../../../../../packages/sdk-vue/src/index.ts');
  const source = readFileSync(indexPath, 'utf8');
  const exports = new Set<string>();
  // Match: export { a, type B, c as Renamed } from '...'
  const namedRe = /export\s+\{([^}]+)\}/g;
  for (const m of source.matchAll(namedRe)) {
    for (const spec of m[1]!.split(',')) {
      const trimmed = spec.trim().replace(/^type\s+/, '');
      const name = trimmed
        .split(/\s+as\s+/)
        .at(-1)
        ?.trim();
      if (name) exports.add(name);
    }
  }
  return exports;
}

interface CatalogFile {
  path: string;
  contents: string;
  lang?: string;
}

function vueFiles(): CatalogFile[] {
  const entry = SDK_CATALOG.find((p) => p.slug === 'vue');
  if (!entry || !('initFiles' in entry) || !entry.initFiles) {
    throw new Error('SDK_CATALOG.vue.initFiles is missing — Phase A migration was reverted?');
  }
  const initFiles = entry.initFiles.map((f) => ({
    path: f.path,
    contents: f.contents,
    lang: f.lang,
  }));
  const extras = 'extras' in entry ? entry.extras : undefined;
  const extrasFiles = extras?.recommendedArchitecture?.files ?? [];
  return [...initFiles, ...extrasFiles.map((f) => ({ ...f }))];
}

function parseTs(filename: string, source: string): readonly ts.Diagnostic[] {
  // `createSourceFile` reports syntactic diagnostics on the returned `parseDiagnostics`
  // property. We don't do a full program typecheck — that would need every transitive
  // dep loaded — but a parse error is the class of bug we're guarding (typo'd keyword,
  // mismatched brace, stray backtick from a missing escape, etc.).
  const sf = ts.createSourceFile(filename, source, ts.ScriptTarget.ES2022, true);
  const parseErrors = (sf as unknown as { parseDiagnostics?: ts.Diagnostic[] }).parseDiagnostics;
  return parseErrors ?? [];
}

function extractSdkVueImports(source: string): string[] {
  // Match: import [type] { a, b as c } from '@arguslog/sdk-vue'
  const out = new Set<string>();
  const re = /import\s+(?:type\s+)?\{([^}]+)\}\s+from\s+['"]@arguslog\/sdk-vue['"]/g;
  for (const m of source.matchAll(re)) {
    for (const spec of m[1]!.split(',')) {
      const name = spec
        .trim()
        .split(/\s+as\s+/)[0]
        ?.trim();
      if (name) out.add(name);
    }
  }
  return [...out];
}

describe('Vue Connect snippet integrity', () => {
  const files = vueFiles();

  it('catalog ships the env-driven Vue installer shape', () => {
    const paths = files.map((f) => f.path);
    expect(paths).toEqual(
      expect.arrayContaining([
        '.env.local',
        'src/arguslog.ts',
        'src/main.ts',
        'src/services/telemetry.ts',
      ]),
    );
  });

  describe.each(files.filter((f) => f.lang === 'ts'))('$path', (file) => {
    it('parses as valid TypeScript', () => {
      const diagnostics = parseTs(file.path, file.contents);
      const messages = diagnostics.map((d) => ts.flattenDiagnosticMessageText(d.messageText, '\n'));
      expect(messages).toEqual([]);
    });

    it('only imports symbols that @arguslog/sdk-vue actually exports', () => {
      const imported = extractSdkVueImports(file.contents);
      if (imported.length === 0) return; // file doesn't import from sdk-vue
      const exported = readSdkVueExports();
      const missing = imported.filter((name) => !exported.has(name));
      expect(missing).toEqual([]);
    });
  });
});
