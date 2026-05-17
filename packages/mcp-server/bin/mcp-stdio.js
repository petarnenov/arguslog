#!/usr/bin/env node
/**
 * Stable bin entry for the stdio MCP server. The committed shim lets pnpm symlink the
 * `mcp-server` / `arguslog-mcp` binaries in consumer workspaces during `pnpm install`,
 * before `dist/` exists — which avoids the chicken-and-egg warning where pnpm fails to
 * create the symlink because the TypeScript build hasn't run yet. At invocation time we
 * forward to the real entry in `dist/index.js`, populated by `pnpm run build` (which the
 * `prepare` script triggers automatically on install when the dist is missing).
 */
import { existsSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const target = resolve(here, '..', 'dist', 'index.js');

if (!existsSync(target)) {
  console.error(
    '[arguslog-mcp] dist/ missing. Run `pnpm --filter @arguslog/mcp-server build` and retry.',
  );
  process.exit(1);
}

await import(target);
