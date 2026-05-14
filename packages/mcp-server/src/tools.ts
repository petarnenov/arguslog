/**
 * Runtime tool catalog. Merges the auto-generated OpenAPI-derived tools with the curated set,
 * preferring curated entries on name collision. Each tool is exposed to MCP as:
 *
 * <ul>
 *   <li>{@code name} — stable identifier the LLM uses when invoking the tool</li>
 *   <li>{@code description} — what / when, in human prose</li>
 *   <li>{@code inputSchema} — JSON Schema describing the args shape</li>
 * </ul>
 *
 * <p>The handler is shared: every tool dispatches through {@link executeTool}, which interprets
 * the tool's recorded {@code method} / {@code path} / {@code paramSpecs} against the
 * {@link ArguslogClient}. Adding new endpoints to the OpenAPI spec → re-run {@code pnpm generate}
 * → tools become callable, no per-tool runtime wiring.
 */
import { buildSyntheticEvent } from '@arguslog/sdk-core';

import type { ArguslogClient } from './client.js';
import { CURATED_TOOLS } from './curated-tools.js';
import {
  OPENAPI_TOOLS,
  type OpenApiTool,
  type OpenApiToolParam,
} from './generated/openapi-tools.js';

/** MCP-shaped tool definition, ready to ship in a {@code tools/list} response. */
export interface McpToolDefinition {
  name: string;
  description: string;
  inputSchema: Record<string, unknown>;
  outputSchema?: Record<string, unknown>;
  annotations?: Record<string, unknown>;
  title?: string;
}

/** Full registry — name → underlying OpenApiTool. Used by both list + call handlers.
 *
 * Merge strategy: when a curated tool and an auto-generated tool target the same HTTP
 * endpoint (same {@code method + path}), we MERGE them — the curated copy contributes
 * {@code name} + {@code description} (LLM-friendly), the auto-generated copy contributes
 * {@code outputSchema} + {@code annotations} + per-param descriptions (from OpenAPI).
 * Without this, we'd ship two callable tools for the same endpoint and inflate the
 * registry (69 entries instead of the canonical 54).
 */
export const TOOL_REGISTRY: Map<string, OpenApiTool> = (() => {
  // Index auto-gen tools by method+path so we can find the "matching" one for each curated.
  const autoByEndpoint = new Map<string, OpenApiTool>();
  for (const t of OPENAPI_TOOLS) {
    autoByEndpoint.set(`${t.method} ${t.path}`, t);
  }

  // Start with auto-gen tools keyed by name.
  const m = new Map<string, OpenApiTool>();
  for (const t of OPENAPI_TOOLS) m.set(t.name, t);

  // Merge curated entries — they replace the auto-gen entry that targets the same endpoint.
  // A curated entry with no matching endpoint is a bug (zombie tool that 404s on call); the
  // build-time guard in scripts/generate-tools.mjs catches it, this throw is the runtime
  // backstop in case the generated module is stale.
  for (const curated of Object.values(CURATED_TOOLS)) {
    const endpointKey = `${curated.method} ${curated.path}`;
    // send_test_event is handled in-process by executeSendTestEvent — it has no api endpoint.
    if (curated.path.startsWith('/internal/mcp/')) {
      m.set(curated.name, curated);
      continue;
    }
    const auto = autoByEndpoint.get(endpointKey);
    if (!auto) {
      throw new Error(
        `Curated MCP tool "${curated.name}" targets ${endpointKey}, which is not in the ` +
          `OpenAPI spec. Re-run \`pnpm --filter @arguslog/mcp-server generate\` after ` +
          `updating services/api/openapi.json, or drop the curated entry.`,
      );
    }
    // Drop the auto-gen entry; replace with a merged one that prefers curated text.
    m.delete(auto.name);
    const merged: OpenApiTool = {
      ...auto,
      ...curated,
      // Pick the richer of the two for these fields when both exist.
      title: curated.title ?? auto.title,
      outputSchema: curated.outputSchema ?? auto.outputSchema,
      annotations: curated.annotations ?? auto.annotations,
      // Wrap flag follows whichever side actually contributed the outputSchema. Curated tools
      // today don't carry their own outputSchema so the auto-gen flag survives the merge.
      outputResultWrapped: curated.outputSchema
        ? curated.outputResultWrapped
        : auto.outputResultWrapped,
    };
    m.set(curated.name, merged);
  }
  return m;
})();

/** Render the registry as MCP tool definitions. */
export function listMcpTools(): McpToolDefinition[] {
  return Array.from(TOOL_REGISTRY.values()).map((t) => {
    const def: McpToolDefinition = {
      name: t.name,
      description: t.description,
      inputSchema: toJsonSchema(t),
    };
    // Optional fields — only present on auto-generated tools (curated tools don't carry
    // them today, but the merge respects whichever is present).
    const anyT = t as unknown as Record<string, unknown>;
    if (anyT.outputSchema && typeof anyT.outputSchema === 'object') {
      def.outputSchema = anyT.outputSchema as Record<string, unknown>;
    }
    if (anyT.annotations && typeof anyT.annotations === 'object') {
      def.annotations = anyT.annotations as Record<string, unknown>;
    }
    if (typeof anyT.title === 'string') {
      def.title = anyT.title;
    }
    return def;
  });
}

/** Shape of the {@code tools/call} success result returned to the MCP transport. Mirrors
 *  the {@code CallToolResultSchema} from {@code @modelcontextprotocol/sdk} — kept loose so
 *  this helper can be the single source of truth for both stdio and HTTP entry points.
 *
 *  The index signature is intentional: it lets the value flow into the SDK's
 *  {@code ServerResult} union (which expects {@code \{ [k:string]: unknown \}}) without
 *  forcing a cast at every call site. */
export interface ToolCallSuccessResult {
  content: Array<{ type: 'text'; text: string }>;
  structuredContent?: Record<string, unknown>;
  [key: string]: unknown;
}

/** Format a tool's raw return value into the MCP-shaped {@code tools/call} result.
 *
 *  <p>When the tool declares an {@code outputSchema} and the result is JSON-shaped, ALSO
 *  emit {@code structuredContent} alongside the text block — MCP spec 2025-11-25 §Tools
 *  says servers with an outputSchema MUST emit structuredContent conforming to it. The
 *  text block stays for backward-compat with pre-2025-11-25 clients that only consume
 *  {@code content}.
 *
 *  <p>For naked-array endpoints the codegen rewraps the schema as
 *  {@code \{type:object, properties:\{result: <orig>\}\}}; runtime mirrors that wrap so
 *  the emitted structuredContent validates.
 */
export function buildToolResult(name: string, result: unknown): ToolCallSuccessResult {
  const text = typeof result === 'string' ? result : JSON.stringify(result, null, 2);
  const content: ToolCallSuccessResult['content'] = [{ type: 'text', text }];

  const tool = TOOL_REGISTRY.get(name);
  // No outputSchema or raw text return → text-only result, no structuredContent. Spec only
  // demands structuredContent when an outputSchema was advertised.
  if (!tool?.outputSchema || result === null || result === undefined) return { content };
  if (typeof result !== 'object' && !Array.isArray(result)) return { content };

  // Mirror the schema-time wrap so the emitted object matches the declared shape.
  const structured = tool.outputResultWrapped ? { result } : (result as Record<string, unknown>);
  // Defensive: if rewrapping somehow produced a non-object (e.g. tool incorrectly flagged),
  // fall back to text-only rather than emit a structuredContent the client will reject.
  if (typeof structured !== 'object' || structured === null || Array.isArray(structured)) {
    return { content };
  }
  return { content, structuredContent: structured };
}

/** Validate args before dispatching to the api. Returns a list of human-readable problems,
 *  empty when args are well-formed. We deliberately stay shallow — required-field presence
 *  and primitive type matching — because:
 *
 *  <ul>
 *    <li>Deeper JSON Schema validation (oneOf, format, etc.) duplicates what the api server
 *        already does and would drift on every spec change.</li>
 *    <li>The LLM-correction loop only needs hints precise enough to revise the next call —
 *        "missing projectId" is much more useful than the api's generic "400 Bad Request".</li>
 *    <li>The api's RFC 9457 problem+json response still surfaces on truly-invalid bodies
 *        we don't catch here, via {@link ArguslogApiError}.</li>
 *  </ul>
 */
function validateArgs(tool: OpenApiTool, args: Record<string, unknown>): string[] {
  const problems: string[] = [];

  const checkParam = (p: OpenApiToolParam, location: 'path' | 'query'): void => {
    const v = args[p.name];
    if (v === undefined || v === null) {
      if (p.required) problems.push(`Missing required ${location} parameter: ${p.name}`);
      return;
    }
    const ok = matchesParamType(v, p.type);
    if (!ok) {
      problems.push(`${location} parameter ${p.name} expected ${p.type}, got ${describeJs(v)}`);
    }
  };
  for (const p of tool.pathParams) checkParam(p, 'path');
  for (const p of tool.queryParams) checkParam(p, 'query');

  if (tool.hasBody) {
    const body = args.body;
    const bodySchema = (tool as unknown as { bodySchema?: Record<string, unknown> }).bodySchema;
    // When the OpenAPI declares required body fields, demand them. Skip deep nested checks —
    // the api enforces those and returns problem+json that we surface verbatim.
    const required = Array.isArray(bodySchema?.required) ? (bodySchema!.required as string[]) : [];
    if (required.length > 0) {
      if (body === undefined || body === null) {
        problems.push(`Missing required body — fields: ${required.join(', ')}`);
      } else if (typeof body !== 'object' || Array.isArray(body)) {
        problems.push(`body must be a JSON object — got ${describeJs(body)}`);
      } else {
        const bodyObj = body as Record<string, unknown>;
        for (const field of required) {
          if (bodyObj[field] === undefined || bodyObj[field] === null) {
            problems.push(`Missing required body field: ${field}`);
          }
        }
      }
    } else if (body !== undefined && body !== null && typeof body !== 'object') {
      problems.push(`body must be a JSON object — got ${describeJs(body)}`);
    }
  }

  return problems;
}

function matchesParamType(v: unknown, expected: OpenApiToolParam['type']): boolean {
  switch (expected) {
    case 'integer':
      return typeof v === 'number' && Number.isInteger(v);
    case 'number':
      return typeof v === 'number' && Number.isFinite(v);
    case 'boolean':
      return typeof v === 'boolean';
    case 'array':
      return Array.isArray(v);
    case 'object':
      return typeof v === 'object' && v !== null && !Array.isArray(v);
    case 'string':
      return typeof v === 'string';
    case 'unknown':
    default:
      return true;
  }
}

function describeJs(v: unknown): string {
  if (v === null) return 'null';
  if (Array.isArray(v)) return 'array';
  return typeof v;
}

/** Execute the tool named {@code name} with {@code args}, dispatching through {@code client}. */
export async function executeTool(
  client: ArguslogClient,
  name: string,
  args: Record<string, unknown>,
): Promise<unknown> {
  // Hand-rolled handler for the test-event probe — needs a two-step flow (api → ingest)
  // that the standard one-tool-one-HTTP-call dispatcher can't express. Intercepted before
  // the registry lookup so the curated metadata (description, schema) still surfaces to
  // the LLM via tools/list without us inventing a fake api endpoint.
  if (name === 'send_test_event') {
    const sendTool = TOOL_REGISTRY.get('send_test_event');
    if (sendTool) {
      const problems = validateArgs(sendTool, args);
      if (problems.length > 0) throw new Error(`Invalid arguments — ${problems.join('; ')}`);
    }
    return executeSendTestEvent(client, args);
  }

  const tool = TOOL_REGISTRY.get(name);
  if (!tool) throw new Error(`Unknown tool: ${name}`);

  const problems = validateArgs(tool, args);
  if (problems.length > 0) throw new Error(`Invalid arguments — ${problems.join('; ')}`);

  // Substitute path params into the URL template, removing them from the args before they get
  // interpreted as query params or body fields.
  const remaining = { ...args };
  let path = tool.path;
  for (const p of tool.pathParams) {
    const v = remaining[p.name];
    if (v === undefined || v === null) {
      // Required path params already failed validation above; this is the optional-param leg.
      continue;
    }
    path = path.replace(`{${p.name}}`, encodeURIComponent(String(v)));
    delete remaining[p.name];
  }

  const query: Record<string, unknown> = {};
  for (const p of tool.queryParams) {
    if (remaining[p.name] !== undefined) {
      query[p.name] = remaining[p.name];
      delete remaining[p.name];
    }
  }

  // Anything not consumed by path / query becomes the body. Curated tools deliberately wrap
  // the body in `{ body: {...} }` so the LLM sees it explicitly.
  let body: unknown = undefined;
  if (tool.hasBody) {
    if ('body' in remaining) {
      body = remaining.body;
      delete remaining.body;
    } else if (Object.keys(remaining).length > 0) {
      body = remaining;
    }
  }

  return client.request({
    method: tool.method,
    path,
    query,
    body,
  });
}

function toJsonSchema(tool: OpenApiTool): Record<string, unknown> {
  const properties: Record<string, unknown> = {};
  const required: string[] = [];

  for (const p of tool.pathParams) {
    properties[p.name] = jsonSchemaForParam(p);
    if (p.required) required.push(p.name);
  }
  for (const p of tool.queryParams) {
    properties[p.name] = jsonSchemaForParam(p);
    if (p.required) required.push(p.name);
  }
  if (tool.hasBody) {
    // Prefer the inlined OpenAPI request body schema — gives the LLM the real field
    // shape (required keys, types) instead of a generic open object. Fall back to the
    // permissive placeholder when codegen couldn't extract a schema (curated tools, or
    // endpoints whose OpenAPI doesn't declare a body schema).
    const inlined = (tool as unknown as { bodySchema?: Record<string, unknown> }).bodySchema;
    properties['body'] =
      inlined && typeof inlined === 'object'
        ? {
            ...inlined,
            description:
              (inlined.description as string | undefined) ??
              'Request body — JSON object matching the OpenAPI request schema.',
          }
        : {
            type: 'object',
            description: 'Request body — JSON object matching the OpenAPI request schema.',
            additionalProperties: true,
          };
  }
  return {
    type: 'object',
    properties,
    required,
    additionalProperties: false,
  };
}

function jsonSchemaForParam(p: OpenApiToolParam): Record<string, unknown> {
  switch (p.type) {
    case 'integer':
      return { type: 'integer', description: paramDescription(p) };
    case 'number':
      return { type: 'number', description: paramDescription(p) };
    case 'boolean':
      return { type: 'boolean', description: paramDescription(p) };
    case 'array':
      return { type: 'array', items: { type: 'string' }, description: paramDescription(p) };
    case 'object':
      return { type: 'object', description: paramDescription(p), additionalProperties: true };
    case 'string':
    case 'unknown':
    default:
      return { type: 'string', description: paramDescription(p) };
  }
}

function paramDescription(p: OpenApiToolParam): string {
  if (p.description && p.description.trim().length > 0) return p.description;
  return p.required ? `${p.name} — required.` : `${p.name} — optional.`;
}

// ── send_test_event custom handler ──────────────────────────────────────────

interface DsnSummary {
  id: number;
  projectId: number;
  dsnPublic: string;
  active: boolean;
  createdAt: string;
}

type Level = 'fatal' | 'error' | 'warning' | 'info' | 'debug';

async function executeSendTestEvent(
  client: ArguslogClient,
  args: Record<string, unknown>,
): Promise<unknown> {
  const projectId = Number(args.projectId);
  if (!Number.isFinite(projectId) || projectId <= 0) {
    throw new Error('send_test_event: projectId is required and must be a positive integer');
  }
  const bodyArg =
    typeof args.body === 'object' && args.body !== null
      ? (args.body as Record<string, unknown>)
      : {};
  const level = (bodyArg.level as Level | undefined) ?? 'error';
  const message = typeof bodyArg.message === 'string' ? bodyArg.message : undefined;

  // Step 1: list the project's keys via the api (PAT, projects:read).
  const keys = await client.request<DsnSummary[]>({
    method: 'GET',
    path: `/api/v1/projects/${projectId}/keys`,
  });
  const active = keys.find((k) => k.active);
  if (!active) {
    throw new Error(
      `project ${projectId} has no active DSN — generate one in the dashboard before send_test_event`,
    );
  }

  // Step 2: derive ingest URL + POST the synthetic event. ARGUSLOG_INGEST_URL takes priority
  // for self-hosted; otherwise we swap api.<host> → ingest.<host>, matching the convention
  // every shipped deployment uses (api.arguslog.org → ingest.arguslog.org, local 8081 → 8080).
  const apiBaseUrl = process.env.ARGUSLOG_API_URL ?? 'https://api.arguslog.org';
  const ingestUrl = (process.env.ARGUSLOG_INGEST_URL ?? deriveIngestUrl(apiBaseUrl)).replace(
    /\/+$/,
    '',
  );
  const payload = buildSyntheticEvent({ level, message, source: 'arguslog/mcp send_test_event' });
  const resp = await fetch(`${ingestUrl}/api/${projectId}/events`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-Arguslog-Auth': `Arguslog DSN ${active.dsnPublic}`,
    },
    body: JSON.stringify(payload),
  });
  if (!resp.ok) {
    const text = await resp.text().catch(() => '');
    throw new Error(
      `ingest rejected synthetic event: HTTP ${resp.status}${text ? ` — ${text.slice(0, 200)}` : ''}`,
    );
  }
  return {
    status: 'accepted',
    eventId: payload.eventId,
    dsnPublic: active.dsnPublic,
    ingestUrl,
    hint: 'Issue appears on the dashboard within ~1s; search synthetic=true to find it.',
  };
}

// Mirror of cli/src/commands/ping.ts deriveIngestUrl — kept duplicated rather than pulling
// the cli module in as a dependency (we want MCP server to stay self-contained).
export function deriveIngestUrl(apiBaseUrl: string): string {
  try {
    const u = new URL(apiBaseUrl);
    if (u.hostname.startsWith('api.')) {
      u.hostname = `ingest.${u.hostname.slice(4)}`;
      return `${u.protocol}//${u.host}`;
    }
    if (u.port === '8081') {
      u.port = '8080';
      return `${u.protocol}//${u.host}`;
    }
    return apiBaseUrl;
  } catch {
    return apiBaseUrl;
  }
}
