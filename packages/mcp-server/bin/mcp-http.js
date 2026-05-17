#!/usr/bin/env node
/**
 * Stable bin entry for the HTTP MCP server. Same shim pattern as mcp-stdio.js — exists
 * pre-build so pnpm install never warns about a missing symlink target.
 */
import { existsSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const target = resolve(here, '..', 'dist', 'http.js');

if (!existsSync(target)) {
  console.error(
    '[arguslog-mcp-http] dist/ missing. Run `pnpm --filter @arguslog/mcp-server build` and retry.',
  );
  process.exit(1);
}

await import(target);
