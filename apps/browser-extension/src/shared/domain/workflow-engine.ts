/**
 * Pure-function step-machine engine for the four curated workflows.
 *
 * Three design rules:
 *
 * 1. **No React, no storage, no MCP transport directly imported.** The engine takes a
 *    `deps` injectable bag for everything that has side effects — that's the seam tests
 *    use to mock without touching real I/O, and the seam the storage layer in the
 *    background uses to attach `workflowRunId` to history entries.
 *
 * 2. **Every transition returns a fresh `WorkflowRunState`.** Callers persist the result
 *    via `setWorkflowState` after each call (see `workflow-run.ts`). The engine never
 *    mutates its input arguments — easier reasoning, easier diffing in the UI.
 *
 * 3. **Per-step capability + approval gating happens here, not in the UI.** If a step's
 *    `tool` isn't in the advertised set the engine marks it `skipped` and stops. If the
 *    step has `requiresApproval` (mutating-tool gate), `runAllRemaining` invokes
 *    `onCheckpoint` BEFORE the step's `run` and refuses to proceed if the checkpoint
 *    returns false. Single-step `advanceStep` assumes the UI already confirmed.
 */

export type StepStatus = 'pending' | 'running' | 'done' | 'error' | 'skipped';
export type RunStatus = 'in_progress' | 'completed' | 'aborted' | 'error';

export interface StepState {
  id: string;
  label: string;
  status: StepStatus;
  /** Args passed to `run` at execution time. Captured for Rerun + display. */
  args?: Record<string, unknown>;
  result?: unknown;
  error?: { bucket?: string; message: string };
  durationMs?: number;
}

export interface WorkflowRunState {
  workflowId: string;
  runId: string;
  inputs: Record<string, unknown>;
  currentStepIndex: number;
  stepStates: StepState[];
  startedAt: string;
  status: RunStatus;
}

export interface StepDefinition {
  id: string;
  label: string;
  /** MCP tool this step calls (for gating + history attribution). Undefined → pure-computation step. */
  tool?: string;
  /** If true, executor must obtain approval before invoking `run`. Mirrors MUTATING_TOOLS. */
  requiresApproval?: boolean;
  /**
   * Build the step's `args` from previous step results + workflow inputs. Caller-supplied
   * overrides take precedence — `prepareArgs` is the default-computation path.
   */
  prepareArgs(state: WorkflowRunState): Record<string, unknown>;
  /**
   * Actual step execution. Throws → engine maps to `status: 'error'`. Returns → stored
   * verbatim under `stepStates[index].result`.
   */
  run(args: Record<string, unknown>, state: WorkflowRunState): Promise<unknown>;
}

export interface WorkflowDefinition {
  id: string;
  steps: StepDefinition[];
  /**
   * Final summarisation pass — derives `markdown` + `json` from completed step results.
   * Pure function. Called when the engine reaches `status: 'completed'` and by the
   * compat shim in `workflows.ts`.
   */
  summarize(state: WorkflowRunState): { markdown: string; json: Record<string, unknown> };
}

/**
 * Dependency-injectable side-effects. Tests pass a stub; runtime callers pass the real
 * ones wired against the existing domain wrappers.
 */
export interface EngineDeps {
  /** Set of MCP tool names the connected server advertises. Empty set → skip the gate. */
  advertisedTools: Set<string>;
}

function freshRunId(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return crypto.randomUUID();
  }
  return `${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
}

export function startRun(
  def: WorkflowDefinition,
  inputs: Record<string, unknown>,
): WorkflowRunState {
  return {
    workflowId: def.id,
    runId: freshRunId(),
    inputs,
    currentStepIndex: 0,
    stepStates: def.steps.map((step) => ({
      id: step.id,
      label: step.label,
      status: 'pending',
    })),
    startedAt: new Date().toISOString(),
    status: 'in_progress',
  };
}

export function abortRun(state: WorkflowRunState): WorkflowRunState {
  return { ...state, status: 'aborted' };
}

/**
 * Advance one step. The UI/runtime is responsible for any approval gate before calling
 * this — `advanceStep` itself never asks. Capability gating IS checked here: a missing
 * tool short-circuits to `skipped` + run-level `status: 'error'`.
 */
export async function advanceStep(
  state: WorkflowRunState,
  def: WorkflowDefinition,
  deps: EngineDeps,
  argsOverride?: Record<string, unknown>,
): Promise<WorkflowRunState> {
  if (state.status !== 'in_progress') return state;
  const idx = state.currentStepIndex;
  if (idx >= def.steps.length) return state;
  const stepDef = def.steps[idx]!;

  // Capability gate.
  if (stepDef.tool && deps.advertisedTools.size > 0 && !deps.advertisedTools.has(stepDef.tool)) {
    const stepStates = withStepUpdate(state.stepStates, idx, {
      status: 'skipped',
      error: {
        message: `Tool "${stepDef.tool}" is not advertised by the connected server.`,
      },
    });
    return { ...state, stepStates, status: 'error' };
  }

  const args = argsOverride ?? stepDef.prepareArgs(state);

  // Mark running so a UI polling the persisted state shows the in-flight badge.
  const beforeRun: WorkflowRunState = {
    ...state,
    stepStates: withStepUpdate(state.stepStates, idx, {
      status: 'running',
      args,
      error: undefined,
      result: undefined,
    }),
  };

  const startedAt = Date.now();
  try {
    const result = await stepDef.run(args, beforeRun);
    const durationMs = Date.now() - startedAt;
    const nextIndex = idx + 1;
    const stepStates = withStepUpdate(beforeRun.stepStates, idx, {
      status: 'done',
      result,
      durationMs,
    });
    const runStatus: RunStatus = nextIndex >= def.steps.length ? 'completed' : 'in_progress';
    return {
      ...beforeRun,
      stepStates,
      currentStepIndex: nextIndex,
      status: runStatus,
    };
  } catch (err) {
    const durationMs = Date.now() - startedAt;
    const error = mapStepError(err);
    return {
      ...beforeRun,
      stepStates: withStepUpdate(beforeRun.stepStates, idx, {
        status: 'error',
        error,
        durationMs,
      }),
      status: 'error',
    };
  }
}

/**
 * Re-execute a specific step. Downstream steps (idx+1 …) are reset to `pending` with
 * their results dropped — they may be invalidated by the rerun's new output. After the
 * rerun, `currentStepIndex` points at `stepIdx + 1` (if rerun succeeded) so the next
 * `advanceStep` continues from there, OR at `stepIdx` itself if it errored/skipped.
 */
export async function rerunStep(
  state: WorkflowRunState,
  def: WorkflowDefinition,
  deps: EngineDeps,
  stepIdx: number,
  argsOverride?: Record<string, unknown>,
): Promise<WorkflowRunState> {
  if (stepIdx < 0 || stepIdx >= def.steps.length) return state;
  // Reset target + every downstream step to pending.
  const stepStates = state.stepStates.map((s, i) =>
    i >= stepIdx
      ? {
          id: s.id,
          label: s.label,
          status: 'pending' as StepStatus,
        }
      : s,
  );
  const reset: WorkflowRunState = {
    ...state,
    stepStates,
    currentStepIndex: stepIdx,
    status: 'in_progress',
  };
  return advanceStep(reset, def, deps, argsOverride);
}

/**
 * Drive every remaining step forward. Mutating steps pause for a checkpoint — the UI
 * binds `onCheckpoint` to a ConfirmDialog. Returning `false` from the checkpoint stops
 * the loop and yields the partially-advanced state (still `in_progress` so the operator
 * can continue manually).
 */
export async function runAllRemaining(
  state: WorkflowRunState,
  def: WorkflowDefinition,
  deps: EngineDeps,
  onCheckpoint: (stepIdx: number, state: WorkflowRunState) => Promise<boolean>,
): Promise<WorkflowRunState> {
  let current = state;
  while (current.status === 'in_progress' && current.currentStepIndex < def.steps.length) {
    const stepDef = def.steps[current.currentStepIndex]!;
    if (stepDef.requiresApproval) {
      const approved = await onCheckpoint(current.currentStepIndex, current);
      if (!approved) return current; // Operator declined — leave state at this checkpoint.
    }
    current = await advanceStep(current, def, deps);
  }
  return current;
}

function withStepUpdate(
  steps: StepState[],
  idx: number,
  patch: Partial<StepState>,
): StepState[] {
  return steps.map((s, i) => (i === idx ? { ...s, ...patch } : s));
}

function mapStepError(err: unknown): { bucket?: string; message: string } {
  if (err && typeof err === 'object' && 'bucket' in err && 'message' in err) {
    return { bucket: String((err as { bucket: unknown }).bucket), message: String((err as { message: unknown }).message) };
  }
  if (err instanceof Error) return { message: err.message };
  return { message: String(err) };
}
