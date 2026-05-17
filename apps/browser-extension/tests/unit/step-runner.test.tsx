/**
 * Tests for the `<StepRunner />` component — the Phase 3 UI for an active workflow run.
 * The workflow-run domain wrapper is mocked so we can assert that the component (a) calls
 * the right transition with the right args, (b) renders the resulting state, and
 * (c) gates mutating steps behind a ConfirmDialog.
 *
 * No real `browser.storage` access — the mocked domain wrapper handles persistence.
 */
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { StepRunner } from '../../src/app/features/workflows/StepRunner';
import type { WorkflowDefinition, WorkflowRunState } from '../../src/shared/domain/workflow-engine';
import { advanceStep, rerunStep, runAllRemaining } from '../../src/shared/domain/workflow-run';

vi.mock('../../src/shared/domain/workflow-run', () => ({
  advanceStep: vi.fn(),
  rerunStep: vi.fn(),
  runAllRemaining: vi.fn(),
}));

function makeDef(): WorkflowDefinition {
  return {
    id: 'arguslog_test',
    steps: [
      {
        id: 'a',
        label: 'Step A',
        tool: 'list_issues',
        prepareArgs: () => ({ from: 'x' }),
        run: async () => 'A',
      },
      {
        id: 'b',
        label: 'Step B',
        tool: 'triage_issue',
        requiresApproval: true,
        prepareArgs: () => ({}),
        run: async () => 'B',
      },
    ],
    summarize: () => ({ markdown: '# done', json: {} }),
  };
}

function pendingState(): WorkflowRunState {
  return {
    workflowId: 'arguslog_test',
    runId: 'run-1234abcd',
    inputs: {},
    currentStepIndex: 0,
    startedAt: new Date().toISOString(),
    status: 'in_progress',
    stepStates: [
      { id: 'a', label: 'Step A', status: 'pending' },
      { id: 'b', label: 'Step B', status: 'pending' },
    ],
  };
}

describe('StepRunner', () => {
  beforeEach(() => {
    vi.mocked(advanceStep).mockReset();
    vi.mocked(rerunStep).mockReset();
    vi.mocked(runAllRemaining).mockReset();
  });

  it('renders pending state with Continue enabled for non-mutating current step', () => {
    render(
      <StepRunner
        def={makeDef()}
        state={pendingState()}
        advertisedTools={new Set(['list_issues', 'triage_issue'])}
        onStateChange={vi.fn()}
        onAbort={vi.fn()}
      />,
    );
    const cont = screen.getByRole('button', { name: 'Continue' });
    expect(cont).toBeEnabled();
    expect(screen.queryByRole('button', { name: 'Apply step' })).toBeNull();
  });

  it('Continue invokes advanceStep + propagates state', async () => {
    const onStateChange = vi.fn();
    const advanced = { ...pendingState(), currentStepIndex: 1 };
    advanced.stepStates[0] = { id: 'a', label: 'Step A', status: 'done', result: 'A' };
    vi.mocked(advanceStep).mockResolvedValue(advanced);

    render(
      <StepRunner
        def={makeDef()}
        state={pendingState()}
        advertisedTools={new Set(['list_issues', 'triage_issue'])}
        onStateChange={onStateChange}
        onAbort={vi.fn()}
      />,
    );
    await userEvent.click(screen.getByRole('button', { name: 'Continue' }));
    await waitFor(() => {
      expect(onStateChange).toHaveBeenCalledWith(advanced);
    });
    expect(advanceStep).toHaveBeenCalledTimes(1);
  });

  it('mutating current step shows Apply + requires ConfirmDialog before advance', async () => {
    const state = pendingState();
    state.currentStepIndex = 1;
    state.stepStates[0] = { id: 'a', label: 'Step A', status: 'done', result: 'A' };
    const advanced = { ...state, currentStepIndex: 2, status: 'completed' as const };
    advanced.stepStates[1] = { id: 'b', label: 'Step B', status: 'done', result: 'B' };
    vi.mocked(advanceStep).mockResolvedValue(advanced);

    render(
      <StepRunner
        def={makeDef()}
        state={state}
        advertisedTools={new Set(['list_issues', 'triage_issue'])}
        onStateChange={vi.fn()}
        onAbort={vi.fn()}
      />,
    );
    expect(screen.queryByRole('button', { name: 'Continue' })).toBeNull();
    const apply = screen.getByRole('button', { name: 'Apply step' });
    await userEvent.click(apply);
    // ConfirmDialog should now be visible — engine not yet invoked.
    expect(advanceStep).not.toHaveBeenCalled();
    const confirm = await screen.findByRole('button', { name: 'Apply' });
    await userEvent.click(confirm);
    await waitFor(() => {
      expect(advanceStep).toHaveBeenCalledTimes(1);
    });
  });

  it('Run all remaining delegates to runAllRemaining', async () => {
    const completed = { ...pendingState(), currentStepIndex: 2, status: 'completed' as const };
    completed.stepStates = [
      { id: 'a', label: 'Step A', status: 'done', result: 'A' },
      { id: 'b', label: 'Step B', status: 'done', result: 'B' },
    ];
    vi.mocked(runAllRemaining).mockResolvedValue(completed);

    render(
      <StepRunner
        def={makeDef()}
        state={pendingState()}
        advertisedTools={new Set(['list_issues', 'triage_issue'])}
        onStateChange={vi.fn()}
        onAbort={vi.fn()}
      />,
    );
    await userEvent.click(screen.getByRole('button', { name: 'Run all remaining' }));
    await waitFor(() => {
      expect(runAllRemaining).toHaveBeenCalledTimes(1);
    });
  });

  it('Edit args opens a textarea + submitting calls rerunStep with parsed args', async () => {
    const state = pendingState();
    state.currentStepIndex = 1;
    state.stepStates[0] = {
      id: 'a',
      label: 'Step A',
      status: 'done',
      args: { from: 'x' },
      result: 'A',
    };
    const reran = { ...state };
    vi.mocked(rerunStep).mockResolvedValue(reran);

    render(
      <StepRunner
        def={makeDef()}
        state={state}
        advertisedTools={new Set(['list_issues', 'triage_issue'])}
        onStateChange={vi.fn()}
        onAbort={vi.fn()}
      />,
    );
    // Step A's expand-and-edit panel needs the user to expand it first.
    await userEvent.click(screen.getByText('Step A'));
    await userEvent.click(screen.getByRole('button', { name: 'Edit args' }));
    const textarea = await screen.findByRole('textbox');
    await userEvent.clear(textarea);
    await userEvent.type(textarea, '{{"from": "y"}');
    await userEvent.click(screen.getByRole('button', { name: 'Rerun with new args' }));
    await waitFor(() => {
      expect(rerunStep).toHaveBeenCalled();
    });
    const call = vi.mocked(rerunStep).mock.calls[0]!;
    expect(call[3]).toBe(0); // stepIdx
    expect(call[4]).toEqual({ from: 'y' });
  });
});
