import { describe, expect, it } from 'vitest';

import * as contract from '../contract.js';
import type { McpToolDefinition, OpenApiTool } from '../contract.js';

/**
 * The browser-safe subpath `@arguslog/mcp-server/contract` is the only public surface for
 * client-side consumers (the issue tracker that opened #1, the future browser-extension
 * SDKs, anything that bundles for the web). This test pins the named exports so an
 * accidental refactor — renaming `CURATED_TOOLS` to `curatedTools`, or moving WORKFLOWS to
 * a different module — surfaces as a test failure here, not as a downstream type error
 * in someone's WXT build.
 *
 * The companion `contract-browser-safety.test.ts` test confirms the EMITTED `dist/
 * contract.js` is free of Node-only requires; this test confirms the source surface.
 */
describe('contract — runtime exports', () => {
  it('exports CURATED_TOOLS as a non-empty record', () => {
    expect(contract.CURATED_TOOLS).toBeTypeOf('object');
    expect(Object.keys(contract.CURATED_TOOLS).length).toBeGreaterThan(0);
  });

  it('exports OPENAPI_TOOLS as a non-empty array', () => {
    expect(Array.isArray(contract.OPENAPI_TOOLS)).toBe(true);
    expect(contract.OPENAPI_TOOLS.length).toBeGreaterThan(0);
  });

  it('exports WORKFLOWS as a non-empty array', () => {
    expect(Array.isArray(contract.WORKFLOWS)).toBe(true);
    expect(contract.WORKFLOWS.length).toBeGreaterThan(0);
  });

  it('exports PACKAGE_NAME and PACKAGE_VERSION as strings', () => {
    expect(typeof contract.PACKAGE_NAME).toBe('string');
    expect(contract.PACKAGE_NAME).toBe('@arguslog/mcp-server');
    expect(typeof contract.PACKAGE_VERSION).toBe('string');
    expect(contract.PACKAGE_VERSION).toMatch(/^\d+\.\d+\.\d+/);
  });
});

describe('contract — type exports compile', () => {
  it('OpenApiTool type is reachable from /contract', () => {
    // The point of this assertion is the type annotation — if `OpenApiTool` ever gets
    // removed from `contract.ts`, this test file won't compile and CI catches it before
    // any consumer feels the break.
    const sample: OpenApiTool = contract.OPENAPI_TOOLS[0]!;
    expect(sample).toBeTypeOf('object');
    expect(sample.name).toBeTypeOf('string');
  });

  it('McpToolDefinition type is reachable from /contract', () => {
    // Construct a structurally-compatible value to lock in the type's surface.
    const def: McpToolDefinition = {
      name: 'noop',
      description: 'placeholder',
      inputSchema: { type: 'object' },
    };
    expect(def.name).toBe('noop');
  });
});
