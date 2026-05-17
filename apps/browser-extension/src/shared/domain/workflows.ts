/**
 * Compat shim — preserves the v1 public API while delegating to the Phase 3 step-machine
 * engine. New callers (StepRunner) talk to the engine directly; legacy callers and the
 * `workflows.test.ts` happy-path coverage keep working without changes.
 *
 * Each `runXxxWorkflow` here drives the engine end-to-end with an empty advertised-tools
 * set (= capability gate disabled, since v1 had no gating) and runs `summarize()` to
 * reproduce the v1 markdown + JSON output. Step badges in the returned `WorkflowResult`
 * are derived from the engine's `stepStates[].status`.
 */
import {
  investigateIssueDefinition,
  regressionCheckDefinition,
  releasePostmortemDefinition,
  triageLoopDefinition,
} from './workflow-definitions';
import type { WorkflowDefinition, WorkflowRunState } from './workflow-engine';
import { advanceStep, startRun } from './workflow-engine';

export interface WorkflowStep {
  id: string;
  label: string;
  status: 'done' | 'error';
  detail?: string;
}

export interface WorkflowResult {
  steps: WorkflowStep[];
  markdown: string;
  json: Record<string, unknown>;
}

async function runDefinitionEndToEnd(
  def: WorkflowDefinition,
  inputs: Record<string, unknown>,
): Promise<WorkflowResult> {
  let state = startRun(def, inputs);
  const deps = { advertisedTools: new Set<string>() };
  while (state.status === 'in_progress' && state.currentStepIndex < def.steps.length) {
    state = await advanceStep(state, def, deps);
  }
  return finalize(state, def);
}

function finalize(state: WorkflowRunState, def: WorkflowDefinition): WorkflowResult {
  const { markdown, json } = def.summarize(state);
  const steps: WorkflowStep[] = state.stepStates.map((s) => ({
    id: s.id,
    label: s.label,
    status: s.status === 'error' ? 'error' : 'done',
    detail: s.error?.message,
  }));
  // If any step errored, surface that error so the legacy caller can see it (matches v1
  // which threw synchronously on `findRelease` miss).
  const errored = state.stepStates.find((s) => s.status === 'error');
  if (errored?.error) {
    throw new Error(errored.error.message);
  }
  return { steps, markdown, json };
}

export async function runInvestigateIssueWorkflow(
  projectId: number,
  issueId: number,
): Promise<WorkflowResult> {
  return runDefinitionEndToEnd(investigateIssueDefinition, { projectId, issueId });
}

export async function runRegressionCheckWorkflow(
  projectId: number,
  currentVersion: string,
  previousVersion: string,
): Promise<WorkflowResult> {
  return runDefinitionEndToEnd(regressionCheckDefinition, {
    projectId,
    currentVersion,
    previousVersion,
  });
}

export async function runReleasePostmortemWorkflow(
  projectId: number,
  version: string,
): Promise<WorkflowResult> {
  return runDefinitionEndToEnd(releasePostmortemDefinition, { projectId, version });
}

export async function runTriageLoopWorkflow(
  projectId: number,
  batchSize: number,
): Promise<WorkflowResult> {
  return runDefinitionEndToEnd(triageLoopDefinition, { projectId, batchSize });
}
