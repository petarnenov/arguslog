/**
 * Browser-safe public contract surface for `@arguslog/mcp-server`.
 *
 * Importing from `@arguslog/mcp-server/contract` pulls in ONLY TypeScript types +
 * plain-data constants — no `@modelcontextprotocol/sdk/server` transport, no
 * `node:crypto`, no `process.env` reads. Safe to bundle in browser extensions (WXT),
 * Vite / Webpack / Rollup web apps, or anywhere TypeScript + structuredClone-safe
 * data are enough.
 *
 *   import {
 *     CURATED_TOOLS,
 *     OPENAPI_TOOLS,
 *     WORKFLOWS,
 *     PACKAGE_VERSION,
 *     type OpenApiTool,
 *     type McpToolDefinition,
 *   } from '@arguslog/mcp-server/contract';
 *
 * The main `@arguslog/mcp-server` entry is the CLI server — it imports server-only
 * MCP SDK transports and is not safe to bundle for the browser. Use the `/contract`
 * subpath for everything client-side.
 *
 * Drift between this barrel and the server-only paths is guarded by
 * `contract-browser-safety.test.ts`, which scans the emitted `dist/contract.js` for
 * Node-only requires (`node:*`, `@modelcontextprotocol/sdk/server/*`, `process.env`).
 * Adding a runtime re-export here that transitively reaches one of those is a CI
 * failure, not a runtime surprise downstream.
 */

// ── Types ─────────────────────────────────────────────────────────────────
// Compile-time-only — `export type` is erased to nothing in the emitted JS, so even
// though `tools.ts` contains a `process.env` read inside its runtime functions, that
// runtime code is never reached through this barrel.
export type {
  OpenApiTool,
  OpenApiToolParam,
  ToolAnnotations,
} from './generated/openapi-tools';
export type { McpToolDefinition } from './tools';

// ── Plain-data constants ──────────────────────────────────────────────────
// structuredClone-safe objects — every consumer (server, extension, any future SDK)
// reads the exact same shape. The auto-generated catalog (OPENAPI_TOOLS) widens with
// the OpenAPI spec; CURATED_TOOLS is the hand-written, LLM-friendly subset.
export { OPENAPI_TOOLS } from './generated/openapi-tools';
export { CURATED_TOOLS } from './curated-tools';
export { WORKFLOWS } from './prompts';

// Package identity — synced from package.json by the codegen pipeline (see
// `scripts/generate-tools.mjs`), so consumers can render a "powered by @arguslog/mcp-
// server vX.Y.Z" footer without their own version drift.
export { PACKAGE_NAME, PACKAGE_VERSION } from './generated/version';

// ── Curated tool-name constants (browser extension uses these for gate logic) ──
// `CURATED_TOOL_NAMES` is the UPPER_SNAKE_CASE → tool-name string map so clients can
// reference tools by symbolic name (e.g. `CURATED_TOOL_NAMES.TRIAGE_ISSUE`). Note this
// is intentionally a separate export from `CURATED_TOOLS` above — the latter is the
// full OpenAPI tool *definition* record keyed by the same names.
export { CURATED_TOOL_NAMES, MUTATING_TOOLS } from './tool-names';
export type { CuratedToolName } from './tool-names';

// ── Workflow IDs + feature gating ──────────────────────────────────────────
// The browser extension's capability registry joins the connected server's advertised
// tool list against `FEATURE_REQUIREMENTS` to know which UI panels to render. These
// are hardcoded (not derived from `prompts.ts`) to avoid pulling the runtime workflow
// bodies into the browser-safe surface.
export { WORKFLOW_IDS, FEATURE_REQUIREMENTS } from './feature-requirements';
export type { WorkflowId } from './feature-requirements';

// ── Zod schemas + inferred domain types ────────────────────────────────────
// Browser-safe (zod has no Node-only deps). Schemas double as runtime validators in
// the extension AND the source of inferred TS types (IssueSummary, IssueDetail, …).
export {
  OrgSummarySchema,
  ProjectSummarySchema,
  MeSchema,
  MemberSchema,
  IssueStatusSchema,
  IssueLevelSchema,
  IssueSummarySchema,
  StackFrameSchema,
  IssueDetailSchema,
  IssueEventSchema,
  ReleaseSummarySchema,
  DsnSchema,
  CreateProjectResultSchema,
  ListProjectsInputSchema,
  ListIssuesInputSchema,
  GetIssueInputSchema,
  ListIssueEventsInputSchema,
  TriageIssueInputSchema,
  AssignIssueInputSchema,
  CreateProjectInputSchema,
  CreateReleaseInputSchema,
  ListMembersInputSchema,
  ListDsnsInputSchema,
  GetReleaseInputSchema,
  ListReleaseInputSchema,
  CuratedToolInputSchemas,
  CuratedToolOutputSchemas,
} from './schemas';
export type {
  OrgSummary,
  ProjectSummary,
  Me,
  Member,
  IssueSummary,
  IssueDetail,
  IssueEvent,
  ReleaseSummary,
  Dsn,
} from './schemas';
