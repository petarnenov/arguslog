/**
 * Per-feature tool-availability requirements consumed by the browser extension's
 * capability registry: the extension renders panels (Issues, Releases, Workflows, …)
 * only after every tool the panel needs is present in the connected MCP server's
 * advertised catalog. Hardcoded here — not derived from the prompts module — so the
 * runtime MCP-server prompts.ts can keep its existing shape (`body`, `name`) without
 * cross-dependencies.
 */
import { CURATED_TOOL_NAMES } from './tool-names.js';

export const WORKFLOW_IDS = {
  TRIAGE_LOOP: 'arguslog_triage_loop',
  RELEASE_POSTMORTEM: 'arguslog_release_postmortem',
  REGRESSION_CHECK: 'arguslog_regression_check',
  INVESTIGATE_ISSUE: 'arguslog_investigate_issue',
} as const;

export type WorkflowId = (typeof WORKFLOW_IDS)[keyof typeof WORKFLOW_IDS];

export interface FeatureRequirements {
  connection: string[];
  workspace: string[];
  issues: string[];
  issueActions: string[];
  releases: string[];
  projects: string[];
  members: string[];
  dsns: string[];
  releasesWrite: string[];
  playbooks: string[];
  advancedTools: string[];
  workflows: Record<WorkflowId, string[]>;
}

export const FEATURE_REQUIREMENTS: FeatureRequirements = {
  connection: [CURATED_TOOL_NAMES.GET_ME],
  workspace: [CURATED_TOOL_NAMES.LIST_MY_ORGS, CURATED_TOOL_NAMES.LIST_PROJECTS],
  issues: [
    CURATED_TOOL_NAMES.LIST_ISSUES,
    CURATED_TOOL_NAMES.GET_ISSUE,
    CURATED_TOOL_NAMES.LIST_ISSUE_EVENTS,
  ],
  issueActions: [CURATED_TOOL_NAMES.TRIAGE_ISSUE, CURATED_TOOL_NAMES.ASSIGN_ISSUE],
  releases: [CURATED_TOOL_NAMES.LIST_RELEASE, CURATED_TOOL_NAMES.GET_RELEASE],
  projects: [CURATED_TOOL_NAMES.CREATE_PROJECT],
  members: [CURATED_TOOL_NAMES.LIST_MEMBERS],
  dsns: [CURATED_TOOL_NAMES.LIST_DSNS],
  releasesWrite: [CURATED_TOOL_NAMES.CREATE_RELEASE],
  playbooks: [],
  advancedTools: [],
  workflows: {
    [WORKFLOW_IDS.TRIAGE_LOOP]: [
      CURATED_TOOL_NAMES.LIST_ISSUES,
      CURATED_TOOL_NAMES.GET_ISSUE,
      CURATED_TOOL_NAMES.TRIAGE_ISSUE,
      CURATED_TOOL_NAMES.ASSIGN_ISSUE,
    ],
    [WORKFLOW_IDS.RELEASE_POSTMORTEM]: [
      CURATED_TOOL_NAMES.LIST_ISSUES,
      CURATED_TOOL_NAMES.GET_ISSUE,
      CURATED_TOOL_NAMES.LIST_RELEASE,
    ],
    [WORKFLOW_IDS.REGRESSION_CHECK]: [
      CURATED_TOOL_NAMES.LIST_ISSUES,
      CURATED_TOOL_NAMES.GET_ISSUE,
      CURATED_TOOL_NAMES.LIST_RELEASE,
    ],
    [WORKFLOW_IDS.INVESTIGATE_ISSUE]: [
      CURATED_TOOL_NAMES.GET_ISSUE,
      CURATED_TOOL_NAMES.LIST_ISSUE_EVENTS,
      CURATED_TOOL_NAMES.TRIAGE_ISSUE,
      CURATED_TOOL_NAMES.ASSIGN_ISSUE,
    ],
  },
};
