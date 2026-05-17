/**
 * String-constant catalog of curated MCP tool names — the source of truth that downstream
 * browser consumers (extension UI, future SDKs) join against when filtering, gating, or
 * confirming dangerous actions. Kept separate from `curated-tools.ts` (which holds the
 * full OpenAPI tool *definitions* keyed by the same names) because clients should not
 * have to import the whole tool catalog to ask "is this name a mutator?".
 */

export const CURATED_TOOL_NAMES = {
  LIST_MY_ORGS: 'list_my_orgs',
  LIST_PROJECTS: 'list_projects',
  LIST_ISSUES: 'list_issues',
  TRIAGE_ISSUE: 'triage_issue',
  ASSIGN_ISSUE: 'assign_issue',
  GET_ISSUE: 'get_issue',
  LIST_ISSUE_EVENTS: 'list_issue_events',
  CREATE_PROJECT: 'create_project',
  CREATE_RELEASE: 'create_release',
  LIST_MEMBERS: 'list_members',
  LIST_DSNS: 'list_dsns',
  GET_ME: 'get_me',
  LIST_RELEASE: 'list_release',
  GET_RELEASE: 'get_release',
} as const;

export type CuratedToolName = (typeof CURATED_TOOL_NAMES)[keyof typeof CURATED_TOOL_NAMES];

/**
 * Tools whose side-effect is a write — the extension's confirm-dialog gate uses this list
 * to know which calls need a "are you sure?" before dispatch. Keep in sync when adding new
 * mutating curated tools.
 */
export const MUTATING_TOOLS: readonly CuratedToolName[] = [
  CURATED_TOOL_NAMES.TRIAGE_ISSUE,
  CURATED_TOOL_NAMES.ASSIGN_ISSUE,
  CURATED_TOOL_NAMES.CREATE_PROJECT,
  CURATED_TOOL_NAMES.CREATE_RELEASE,
] as const;
