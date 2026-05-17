/**
 * Parity test: the inline SDK_CATALOG in apps/web/src/lib/connectSnippets.ts MUST stay in
 * lockstep with the single-source-of-truth SQL migration in
 * services/api/src/main/resources/db/migration/R__platforms_catalog.sql. The backend has its
 * own PlatformsCatalogParityTest that pins (slug, sdk_package, sdk_version) against the SDK
 * manifests; this is the frontend counterpart so the magic-prompt builder never inlines a
 * stale version into instructions handed to a coding agent.
 */
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { describe, expect, it } from 'vitest';

import { SDK_CATALOG } from '../../lib/connectSnippets';

const MIGRATION = resolve(
  __dirname,
  '../../../../../services/api/src/main/resources/db/migration/R__platforms_catalog.sql',
);

interface SqlRow {
  slug: string;
  pkg: string;
  version: string;
}

/**
 * Parse the `INSERT INTO platforms ... VALUES (...)` block. We tolerate whitespace and quoting
 * variations but expect each row on a single line in the migration — same shape the backend
 * test assumes.
 */
function parseSqlRows(): SqlRow[] {
  const sql = readFileSync(MIGRATION, 'utf8');
  const rowPattern = /\(\s*'([^']+)'\s*,\s*'[^']*'\s*,\s*'([^']+)'\s*,\s*'([^']+)'\s*,\s*\d+\s*\)/g;
  const rows: SqlRow[] = [];
  let match: RegExpExecArray | null;
  while ((match = rowPattern.exec(sql)) !== null) {
    rows.push({ slug: match[1]!, pkg: match[2]!, version: match[3]! });
  }
  return rows;
}

describe('SDK_CATALOG parity vs R__platforms_catalog.sql', () => {
  const sqlRows = parseSqlRows();

  it('parser actually found rows (sanity)', () => {
    expect(sqlRows.length).toBeGreaterThanOrEqual(10);
  });

  it('covers every platform slug from the SQL catalog', () => {
    const tsSlugs = new Set(SDK_CATALOG.map((p) => p.slug));
    const sqlSlugs = new Set(sqlRows.map((r) => r.slug));
    expect(tsSlugs).toEqual(sqlSlugs);
  });

  it('pins identical (slug, pkg, version) tuples on every row', () => {
    for (const sql of sqlRows) {
      const ts = SDK_CATALOG.find((p) => p.slug === sql.slug);
      expect(ts, `missing TS row for slug ${sql.slug}`).toBeDefined();
      expect(ts!.pkg, `pkg drift for ${sql.slug}`).toBe(sql.pkg);
      expect(ts!.version, `version drift for ${sql.slug}`).toBe(sql.version);
    }
  });
});
