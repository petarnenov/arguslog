/**
 * Cross-SDK integrity smoke for every SDK_CATALOG entry that ships `initFiles[]`.
 *
 * The "createArguslog vs arguslogPlugin" drift slipped through because nothing in CI
 * actually parsed the catalog snippet text or cross-checked the imported symbols
 * against what the SDK exports. This file closes that hole for every SDK that ships
 * the env-driven `initFiles[]` shape (currently Vue + React; Next.js / Angular /
 * React Native land later in the cross-SDK rework — once they migrate they're
 * automatically covered here).
 *
 * Two cheap mechanical checks per SDK:
 *
 *   1. Every TS file in `entry.initFiles[]` (and any
 *      `entry.extras.recommendedArchitecture.files[]`) parses as valid TypeScript.
 *      A typo or stray backtick gets flagged by `ts.createSourceFile`.
 *
 *   2. Every symbol the snippet imports from the SDK's npm package is actually
 *      exported by the SDK's `src/index.ts`. If someone renames `createArguslog`
 *      or removes `installVueErrorHandler`, this fails LOUD in CI rather than
 *      silently in user copy-paste land.
 *
 * Light-weight by design: no `vue-tsc`, no temp project, no subprocess. The full
 * type check is a future enhancement; what we cover here catches the bug class
 * the original issue reporter actually hit.
 */
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import ts from 'typescript';
import { describe, expect, it } from 'vitest';

import { SDK_CATALOG } from '../../lib/connectSnippets';

interface CatalogFile {
  path: string;
  contents: string;
  lang?: string;
}

interface SdkUnderTest {
  slug: string;
  /** npm package name as it appears in snippet imports (`@arguslog/sdk-<X>`). */
  pkg: string;
  /** Absolute path to the SDK's source `index.ts` so we can read its exports. */
  indexPath: string;
  files: CatalogFile[];
}

const SDK_FIXTURES: SdkUnderTest[] = collectSdksUnderTest();

function collectSdksUnderTest(): SdkUnderTest[] {
  const out: SdkUnderTest[] = [];
  for (const entry of SDK_CATALOG) {
    if (!('initFiles' in entry) || !entry.initFiles) continue;
    const initFiles = entry.initFiles.map((f) => ({ ...f }));
    const extras = 'extras' in entry ? entry.extras : undefined;
    const extrasFiles = extras?.recommendedArchitecture?.files ?? [];
    const indexPath = resolve(__dirname, `../../../../../packages/sdk-${entry.slug}/src/index.ts`);
    out.push({
      slug: entry.slug,
      pkg: entry.pkg,
      indexPath,
      files: [...initFiles, ...extrasFiles.map((f) => ({ ...f }))],
    });
  }
  return out;
}

function readSdkExports(indexPath: string): Set<string> {
  const source = readFileSync(indexPath, 'utf8');
  const exports = new Set<string>();
  // Match: export { a, type B, c as Renamed } from '...'
  // (also matches export { ... } without `from` for re-locals)
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

function parseTs(filename: string, source: string): readonly ts.Diagnostic[] {
  const sf = ts.createSourceFile(filename, source, ts.ScriptTarget.ES2022, true);
  const parseErrors = (sf as unknown as { parseDiagnostics?: ts.Diagnostic[] }).parseDiagnostics;
  return parseErrors ?? [];
}

function extractPkgImports(source: string, pkg: string): string[] {
  const out = new Set<string>();
  // Escape pkg name for regex (the `@arguslog/sdk-foo` slash is safe but be defensive).
  const escapedPkg = pkg.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const re = new RegExp(
    `import\\s+(?:type\\s+)?\\{([^}]+)\\}\\s+from\\s+['"]${escapedPkg}['"]`,
    'g',
  );
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

describe('Cross-SDK Connect snippet integrity', () => {
  it('catalog includes at least one workflow-first SDK', () => {
    expect(SDK_FIXTURES.length).toBeGreaterThan(0);
  });

  describe.each(SDK_FIXTURES)('$slug', (sdk) => {
    const tsFiles = sdk.files.filter((f) => f.lang === 'ts' || f.lang === 'tsx');

    it('ships at least one TS/TSX file in the env-driven installer shape', () => {
      expect(tsFiles.length).toBeGreaterThan(0);
    });

    describe.each(tsFiles)('$path', (file) => {
      it('parses as valid TypeScript', () => {
        const diagnostics = parseTs(file.path, file.contents);
        const messages = diagnostics.map((d) =>
          ts.flattenDiagnosticMessageText(d.messageText, '\n'),
        );
        expect(messages).toEqual([]);
      });

      it(`only imports symbols that ${sdk.pkg} actually exports`, () => {
        const imported = extractPkgImports(file.contents, sdk.pkg);
        if (imported.length === 0) return;
        const exported = readSdkExports(sdk.indexPath);
        const missing = imported.filter((name) => !exported.has(name));
        expect(missing).toEqual([]);
      });
    });
  });
});
