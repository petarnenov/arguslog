/**
 * Barrel export — every curated workflow's step-machine definition, keyed by canonical
 * `WORKFLOW_IDS` (from `@arguslog/mcp-server/contract`). Consumers look up the
 * definition via `WORKFLOW_DEFINITIONS[workflowId]`.
 *
 * `withMutatingApproval` lifts `MUTATING_TOOLS` membership into engine-level
 * `requiresApproval` so `runAllRemaining` pauses for the UI ConfirmDialog at any step
 * whose tool would change server state. Today all four workflows are read-only, but the
 * gate is wired so future mutating steps inherit defense-in-depth without re-plumbing.
 */
import { MUTATING_TOOLS, type WorkflowId } from '@arguslog/mcp-server/contract';

import type { WorkflowDefinition } from '../workflow-engine';
import { investigateIssueDefinition } from './investigate-issue';
import { regressionCheckDefinition } from './regression-check';
import { releasePostmortemDefinition } from './release-postmortem';
import { triageLoopDefinition } from './triage-loop';

function withMutatingApproval(def: WorkflowDefinition): WorkflowDefinition {
  return {
    ...def,
    steps: def.steps.map((step) =>
      step.tool && MUTATING_TOOLS.includes(step.tool as (typeof MUTATING_TOOLS)[number])
        ? { ...step, requiresApproval: true }
        : step,
    ),
  };
}

export const WORKFLOW_DEFINITIONS: Record<WorkflowId, WorkflowDefinition> = {
  arguslog_investigate_issue: withMutatingApproval(investigateIssueDefinition),
  arguslog_regression_check: withMutatingApproval(regressionCheckDefinition),
  arguslog_release_postmortem: withMutatingApproval(releasePostmortemDefinition),
  arguslog_triage_loop: withMutatingApproval(triageLoopDefinition),
};

export {
  investigateIssueDefinition,
  regressionCheckDefinition,
  releasePostmortemDefinition,
  triageLoopDefinition,
};
