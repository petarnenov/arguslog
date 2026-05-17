/**
 * Tests for the pure-function step-machine engine. The engine has no I/O — every test
 * builds a tiny mock `WorkflowDefinition` and walks its lifecycle. No mocks of
 * browser.storage / mcp-transport / catalog are needed.
 *
 * The 7 cases cover the AC1 / AC4 / AC5 / AC6 transitions:
 *   - startRun shape
 *   - advanceStep happy path → status + index progression
 *   - advanceStep error → run-level error, index frozen
 *   - rerunStep resets downstream + re-executes target
 *   - abortRun final status
 *   - runAllRemaining invokes onCheckpoint for mutating steps
 *   - capability gate skips + halts when tool is missing
 */
import { describe, expect, it, vi } from 'vitest';

import {
  abortRun,
  advanceStep,
  rerunStep,
  runAllRemaining,
  startRun,
  type EngineDeps,
  type WorkflowDefinition,
} from '../../src/shared/domain/workflow-engine';

function makeDef(): WorkflowDefinition {
  return {
    id: 'arguslog_test',
    steps: [
      {
        id: 'a',
        label: 'Step A',
        tool: 'list_issues',
        prepareArgs: (state) => ({ from: state.inputs.from as string }),
        run: vi.fn(async (args) => ({ stepA: true, args })),
      },
      {
        id: 'b',
        label: 'Step B',
        tool: 'triage_issue',
        requiresApproval: true,
        prepareArgs: (state) => ({ prev: state.stepStates[0]?.result }),
        run: vi.fn(async () => ({ stepB: true })),
      },
    ],
    summarize: (state) => ({
      markdown: '# test',
      json: { results: state.stepStates.map((s) => s.result) },
    }),
  };
}

const deps: EngineDeps = { advertisedTools: new Set(['list_issues', 'triage_issue']) };

describe('workflow-engine', () => {
  it('startRun seeds every step as pending + index 0', () => {
    const def = makeDef();
    const state = startRun(def, { from: 'x' });
    expect(state.workflowId).toBe('arguslog_test');
    expect(state.currentStepIndex).toBe(0);
    expect(state.status).toBe('in_progress');
    expect(state.stepStates.map((s) => s.status)).toEqual(['pending', 'pending']);
    expect(state.stepStates[0]?.id).toBe('a');
    expect(state.runId).toMatch(/.+/);
  });

  it('advanceStep happy path → done + index increments', async () => {
    const def = makeDef();
    let state = startRun(def, { from: 'x' });
    state = await advanceStep(state, def, deps);
    expect(state.currentStepIndex).toBe(1);
    expect(state.stepStates[0]?.status).toBe('done');
    expect(state.stepStates[0]?.result).toEqual({ stepA: true, args: { from: 'x' } });
    expect(state.status).toBe('in_progress');
  });

  it('advanceStep error → status error, index frozen, step error captured', async () => {
    const def = makeDef();
    def.steps[0]!.run = vi.fn(async () => {
      throw new Error('boom');
    });
    let state = startRun(def, { from: 'x' });
    state = await advanceStep(state, def, deps);
    expect(state.status).toBe('error');
    expect(state.stepStates[0]?.status).toBe('error');
    expect(state.stepStates[0]?.error?.message).toBe('boom');
    // currentStepIndex did not advance because the step failed.
    expect(state.currentStepIndex).toBe(0);
  });

  it('rerunStep resets target + downstream to pending and re-executes', async () => {
    const def = makeDef();
    let state = startRun(def, { from: 'x' });
    state = await advanceStep(state, def, deps); // a done, idx 1
    state = await advanceStep(state, def, deps); // b done, idx 2, completed
    expect(state.status).toBe('completed');

    state = await rerunStep(state, def, deps, 0, { from: 'override' });
    expect(state.stepStates[0]?.status).toBe('done');
    expect(state.stepStates[0]?.args).toEqual({ from: 'override' });
    // Step B should have been reset by rerunStep then re-advanced into pending state
    // — the rerun only re-runs the target, not downstream steps.
    expect(state.stepStates[1]?.status).toBe('pending');
    expect(state.currentStepIndex).toBe(1);
  });

  it('abortRun marks status aborted without touching steps', () => {
    const def = makeDef();
    const state = startRun(def, { from: 'x' });
    const aborted = abortRun(state);
    expect(aborted.status).toBe('aborted');
    expect(aborted.stepStates).toBe(state.stepStates);
  });

  it('runAllRemaining invokes onCheckpoint for each mutating step', async () => {
    const def = makeDef();
    const checkpoint = vi.fn(async () => true);
    let state = startRun(def, { from: 'x' });
    state = await runAllRemaining(state, def, deps, checkpoint);
    expect(state.status).toBe('completed');
    // Step A is not mutating → no checkpoint. Step B is mutating → 1 checkpoint.
    expect(checkpoint).toHaveBeenCalledTimes(1);
    expect(checkpoint).toHaveBeenCalledWith(1, expect.objectContaining({ currentStepIndex: 1 }));
  });

  it('runAllRemaining halts when checkpoint refuses', async () => {
    const def = makeDef();
    const checkpoint = vi.fn(async () => false);
    let state = startRun(def, { from: 'x' });
    state = await runAllRemaining(state, def, deps, checkpoint);
    // Step A ran (no checkpoint), then B paused on rejected checkpoint.
    expect(state.stepStates[0]?.status).toBe('done');
    expect(state.stepStates[1]?.status).toBe('pending');
    expect(state.status).toBe('in_progress');
  });

  it('capability gate → step skipped + run halts when tool is missing', async () => {
    const def = makeDef();
    const limitedDeps: EngineDeps = { advertisedTools: new Set(['triage_issue']) };
    let state = startRun(def, { from: 'x' });
    state = await advanceStep(state, def, limitedDeps);
    expect(state.stepStates[0]?.status).toBe('skipped');
    expect(state.stepStates[0]?.error?.message).toMatch(/list_issues/);
    expect(state.status).toBe('error');
    // run was not invoked because the gate fired first.
    expect(def.steps[0]!.run).not.toHaveBeenCalled();
  });
});
