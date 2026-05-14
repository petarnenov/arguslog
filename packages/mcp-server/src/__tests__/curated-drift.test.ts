import { describe, expect, it } from 'vitest';

import { CURATED_TOOLS } from '../curated-tools.js';
import { OPENAPI_TOOLS } from '../generated/openapi-tools.js';

/**
 * Every curated tool overrides a real OpenAPI endpoint. If the API drops an endpoint and
 * `curated-tools.ts` isn't updated, the merge in `tools.ts` would still ship the curated
 * entry — a zombie tool that 404s on call. The build-time guard in `generate-tools.mjs`
 * catches this when the spec is regenerated; this test is the source-of-truth check that
 * also catches it without re-running codegen (e.g. an editor save that only touched
 * curated-tools.ts).
 *
 * Carve-out: `/internal/mcp/*` paths are handled in-process by the MCP server itself
 * (e.g. send_test_event's two-step api→ingest flow) and deliberately have no api endpoint.
 */
describe('curated tools — openapi drift', () => {
  const openApiEndpoints = new Set(OPENAPI_TOOLS.map((t) => `${t.method} ${t.path}`));

  for (const [key, tool] of Object.entries(CURATED_TOOLS)) {
    it(`${key} → ${tool.method} ${tool.path} exists in openapi.json`, () => {
      if (tool.path.startsWith('/internal/mcp/')) return; // in-process handler, no api endpoint
      const endpointKey = `${tool.method} ${tool.path}`;
      expect(openApiEndpoints).toContain(endpointKey);
    });
  }
});
