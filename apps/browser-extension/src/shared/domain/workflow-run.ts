/**
 * Domain wrapper around the persisted active workflow run. The engine itself is pure
 * (`workflow-engine.ts`); this module owns:
 *   - persistence: reads/writes the run state through `workspace-store`'s session
 *     storage (key `'workspace.workflowState'`).
 *   - attribution: `runStep` / `runAllRemaining` wrap the underlying engine calls in
 *     `withWorkflowRun(runId, …)` so every `callRawTool` invocation made by a step's
 *     `run` function lands in execution-history with `workflowRunId` stamped on it.
 *
 * The persisted state is validated with `WorkflowRunStateSchema` — invalid blobs (e.g.
 * after a schema bump) round-trip as `undefined` so the UI falls back to the launcher.
 */
import { z } from 'zod';

import { clearWorkflowState, getWorkflowState, setWorkflowState } from '../storage/workspace-store';

import { withWorkflowRun } from './catalog';
import {
  abortRun as engineAbortRun,
  advanceStep as engineAdvanceStep,
  rerunStep as engineRerunStep,
  runAllRemaining as engineRunAllRemaining,
  startRun as engineStartRun,
  type EngineDeps,
  type WorkflowDefinition,
  type WorkflowRunState,
} from './workflow-engine';

const StepStateSchema = z.object({
  id: z.string(),
  label: z.string(),
  status: z.enum(['pending', 'running', 'done', 'error', 'skipped']),
  args: z.record(z.unknown()).optional(),
  result: z.unknown().optional(),
  error: z.object({ bucket: z.string().optional(), message: z.string() }).optional(),
  durationMs: z.number().optional(),
});

const WorkflowRunStateSchema = z.object({
  workflowId: z.string(),
  runId: z.string(),
  inputs: z.record(z.unknown()),
  currentStepIndex: z.number().int().nonnegative(),
  stepStates: z.array(StepStateSchema),
  startedAt: z.string(),
  status: z.enum(['in_progress', 'completed', 'aborted', 'error']),
});

export async function getActiveRun(): Promise<WorkflowRunState | undefined> {
  const raw = await getWorkflowState<unknown>();
  const parsed = WorkflowRunStateSchema.safeParse(raw);
  return parsed.success ? (parsed.data as WorkflowRunState) : undefined;
}

export async function saveRun(state: WorkflowRunState): Promise<void> {
  await setWorkflowState(state);
}

export async function clearRun(): Promise<void> {
  await clearWorkflowState();
}

export function startRun(
  def: WorkflowDefinition,
  inputs: Record<string, unknown>,
): WorkflowRunState {
  return engineStartRun(def, inputs);
}

export function abortRun(state: WorkflowRunState): WorkflowRunState {
  return engineAbortRun(state);
}

/**
 * Advance one step + persist + attribute history entries. The single entry point the
 * UI should use for forward progress — direct calls into the engine bypass attribution.
 */
export async function advanceStep(
  state: WorkflowRunState,
  def: WorkflowDefinition,
  deps: EngineDeps,
  argsOverride?: Record<string, unknown>,
): Promise<WorkflowRunState> {
  const next = await withWorkflowRun(state.runId, () =>
    engineAdvanceStep(state, def, deps, argsOverride),
  );
  await saveRun(next);
  return next;
}

export async function rerunStep(
  state: WorkflowRunState,
  def: WorkflowDefinition,
  deps: EngineDeps,
  stepIdx: number,
  argsOverride?: Record<string, unknown>,
): Promise<WorkflowRunState> {
  const next = await withWorkflowRun(state.runId, () =>
    engineRerunStep(state, def, deps, stepIdx, argsOverride),
  );
  await saveRun(next);
  return next;
}

export async function runAllRemaining(
  state: WorkflowRunState,
  def: WorkflowDefinition,
  deps: EngineDeps,
  onCheckpoint: (stepIdx: number, state: WorkflowRunState) => Promise<boolean>,
): Promise<WorkflowRunState> {
  const next = await withWorkflowRun(state.runId, () =>
    engineRunAllRemaining(state, def, deps, onCheckpoint),
  );
  await saveRun(next);
  return next;
}
