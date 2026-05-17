/**
 * Phase 3 step-runner UI. Renders the active workflow run as a vertical step list with
 * status badges + collapsible result blocks; current-step actions are Continue (or
 * Apply, for mutating steps), Edit args (inline JSON form), Rerun, Run all remaining,
 * and Abort.
 *
 * Persistence is owned by `workflow-run.ts` — every engine transition routes through
 * `advanceStep`/`rerunStep`/`runAllRemaining` which `saveRun()` themselves, so the
 * component just needs to call `setState(next)` and React Query / parent props stay
 * in sync. Side-panel reopen reads the persisted state via `getActiveRun()` so the run
 * resumes at the same step (AC3).
 */
import { useState } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';

import { ConfirmDialog } from '../../../shared/ui/components/ConfirmDialog';
import {
  Badge,
  Button,
  Card,
  EmptyState,
  InlineError,
  Textarea,
} from '../../../shared/ui/components/primitives';
import {
  advanceStep,
  rerunStep,
  runAllRemaining,
} from '../../../shared/domain/workflow-run';
import type {
  StepState,
  StepStatus,
  WorkflowDefinition,
  WorkflowRunState,
} from '../../../shared/domain/workflow-engine';
import { copyText, downloadFile } from '../../../shared/utils/export';

interface Props {
  def: WorkflowDefinition;
  state: WorkflowRunState;
  advertisedTools: Set<string>;
  onStateChange: (next: WorkflowRunState) => void;
  onAbort: () => void;
}

function statusTone(status: StepStatus): 'default' | 'success' | 'warn' | 'danger' {
  switch (status) {
    case 'done':
      return 'success';
    case 'running':
      return 'warn';
    case 'error':
    case 'skipped':
      return 'danger';
    default:
      return 'default';
  }
}

function formatJson(value: unknown): string {
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return String(value);
  }
}

function parseArgs(text: string): { ok: true; value: Record<string, unknown> } | { ok: false; error: string } {
  if (!text.trim()) return { ok: true, value: {} };
  try {
    const parsed = JSON.parse(text);
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
      return { ok: false, error: 'Args must be a JSON object.' };
    }
    return { ok: true, value: parsed as Record<string, unknown> };
  } catch (err) {
    return { ok: false, error: err instanceof Error ? err.message : 'Invalid JSON.' };
  }
}

function StepCard(props: {
  index: number;
  step: StepState;
  isCurrent: boolean;
  isMutating: boolean;
  isExpanded: boolean;
  onToggleExpand: () => void;
}) {
  const { step, isCurrent, isMutating } = props;
  return (
    <Card className={isCurrent ? 'border-blue-400/60' : undefined}>
      <button
        type="button"
        onClick={props.onToggleExpand}
        className="flex w-full items-center justify-between gap-3 text-left"
      >
        <div className="flex items-center gap-3">
          <span className="text-xs font-mono text-slate-500">{props.index + 1}.</span>
          <span className="text-sm font-medium text-slate-100">{step.label}</span>
          {isMutating ? <Badge tone="danger">mutating</Badge> : null}
        </div>
        <Badge tone={statusTone(step.status)}>{step.status}</Badge>
      </button>
      {props.isExpanded ? (
        <div className="mt-3 space-y-2 border-t border-slate-800 pt-3 text-xs">
          {step.args ? (
            <div>
              <p className="font-medium text-slate-400">Args</p>
              <pre className="mt-1 overflow-x-auto rounded bg-slate-950/50 p-2 text-slate-200">
                {formatJson(step.args)}
              </pre>
            </div>
          ) : null}
          {step.result !== undefined ? (
            <div>
              <p className="font-medium text-slate-400">Result</p>
              <pre className="mt-1 max-h-48 overflow-auto rounded bg-slate-950/50 p-2 text-slate-200">
                {formatJson(step.result)}
              </pre>
            </div>
          ) : null}
          {step.error ? <InlineError message={step.error.message} /> : null}
          {step.durationMs !== undefined ? (
            <p className="text-slate-500">Duration: {step.durationMs}ms</p>
          ) : null}
        </div>
      ) : null}
    </Card>
  );
}

export function StepRunner(props: Props) {
  const { def, state, advertisedTools, onStateChange, onAbort } = props;
  const [expandedIdx, setExpandedIdx] = useState<number | null>(state.currentStepIndex);
  const [argsDraft, setArgsDraft] = useState<string>('');
  const [editingIdx, setEditingIdx] = useState<number | null>(null);
  const [parseError, setParseError] = useState<string | undefined>();
  const [pendingApproval, setPendingApproval] = useState<{
    stepIdx: number;
    mode: 'advance' | 'run-all';
  } | null>(null);
  const [inFlight, setInFlight] = useState(false);

  const deps = { advertisedTools };
  const currentIdx = state.currentStepIndex;
  const currentStep: StepState | undefined = state.stepStates[currentIdx];
  const currentDef = def.steps[currentIdx];
  const isMutatingCurrent = !!currentDef?.requiresApproval;
  const isRunComplete = state.status === 'completed';
  const isRunErrored = state.status === 'error';
  const summary = isRunComplete ? def.summarize(state) : undefined;

  function getDefaultArgs(stepIdx: number): Record<string, unknown> {
    const stepDef = def.steps[stepIdx];
    if (!stepDef) return {};
    try {
      return stepDef.prepareArgs(state);
    } catch {
      return {};
    }
  }

  function openArgsEditor(stepIdx: number) {
    const existing = state.stepStates[stepIdx]?.args ?? getDefaultArgs(stepIdx);
    setEditingIdx(stepIdx);
    setArgsDraft(formatJson(existing));
    setParseError(undefined);
  }

  async function doAdvance(argsOverride?: Record<string, unknown>) {
    if (inFlight) return;
    setInFlight(true);
    try {
      const next = await advanceStep(state, def, deps, argsOverride);
      onStateChange(next);
      setExpandedIdx(next.currentStepIndex);
    } finally {
      setInFlight(false);
    }
  }

  async function doRerun(stepIdx: number, argsOverride?: Record<string, unknown>) {
    if (inFlight) return;
    setInFlight(true);
    try {
      const next = await rerunStep(state, def, deps, stepIdx, argsOverride);
      onStateChange(next);
      setExpandedIdx(stepIdx);
      setEditingIdx(null);
    } finally {
      setInFlight(false);
    }
  }

  async function doRunAll() {
    if (inFlight) return;
    setInFlight(true);
    try {
      const next = await runAllRemaining(state, def, deps, async (stepIdx) => {
        // The engine pauses here for every mutating step. We resolve via a one-shot
        // promise wired through the ConfirmDialog so the operator confirms inline.
        return new Promise<boolean>((resolve) => {
          setPendingApproval({ stepIdx, mode: 'run-all' });
          (window as unknown as { __workflowApprovalResolve?: (v: boolean) => void }).__workflowApprovalResolve = resolve;
        });
      });
      onStateChange(next);
      setExpandedIdx(next.currentStepIndex);
    } finally {
      setInFlight(false);
    }
  }

  function resolveApproval(approved: boolean) {
    const resolver = (window as unknown as { __workflowApprovalResolve?: (v: boolean) => void })
      .__workflowApprovalResolve;
    if (resolver) {
      resolver(approved);
      delete (window as unknown as { __workflowApprovalResolve?: (v: boolean) => void })
        .__workflowApprovalResolve;
    }
    setPendingApproval(null);
  }

  function submitArgsEdit() {
    if (editingIdx === null) return;
    const parsed = parseArgs(argsDraft);
    if (!parsed.ok) {
      setParseError(parsed.error);
      return;
    }
    setParseError(undefined);
    void doRerun(editingIdx, parsed.value);
  }

  return (
    <div className="space-y-3">
      <Card>
        <div className="flex flex-wrap items-center justify-between gap-2">
          <div className="text-xs text-slate-400">
            Run <span className="font-mono text-slate-200">{state.runId.slice(0, 8)}</span> ·
            status <Badge tone={isRunErrored ? 'danger' : isRunComplete ? 'success' : 'warn'}>
              {state.status}
            </Badge>{' '}
            · step {Math.min(currentIdx + 1, def.steps.length)} of {def.steps.length}
          </div>
          <div className="flex gap-2">
            {!isRunComplete ? (
              <>
                <Button
                  variant="secondary"
                  disabled={inFlight || isRunComplete}
                  onClick={doRunAll}
                >
                  Run all remaining
                </Button>
                <Button
                  variant={isMutatingCurrent ? 'danger' : 'primary'}
                  disabled={inFlight || !currentStep || currentStep.status === 'running'}
                  onClick={() => {
                    if (isMutatingCurrent) {
                      setPendingApproval({ stepIdx: currentIdx, mode: 'advance' });
                    } else {
                      void doAdvance();
                    }
                  }}
                >
                  {isMutatingCurrent ? 'Apply step' : 'Continue'}
                </Button>
              </>
            ) : null}
            <Button variant="ghost" onClick={onAbort}>
              {isRunComplete ? 'Close' : 'Abort'}
            </Button>
          </div>
        </div>
      </Card>

      <div className="space-y-2">
        {state.stepStates.map((step, idx) => (
          <div key={step.id} className="space-y-2">
            <StepCard
              index={idx}
              step={step}
              isCurrent={idx === currentIdx && !isRunComplete}
              isMutating={!!def.steps[idx]?.requiresApproval}
              isExpanded={expandedIdx === idx}
              onToggleExpand={() => setExpandedIdx(expandedIdx === idx ? null : idx)}
            />
            {expandedIdx === idx && step.status !== 'pending' ? (
              <div className="flex flex-wrap gap-2 px-1">
                <Button variant="secondary" onClick={() => openArgsEditor(idx)}>
                  Edit args
                </Button>
                <Button variant="secondary" onClick={() => void doRerun(idx)}>
                  Rerun step
                </Button>
              </div>
            ) : null}
            {editingIdx === idx ? (
              <Card>
                <div className="space-y-2">
                  <p className="text-xs font-medium text-slate-300">Override args (JSON)</p>
                  <Textarea
                    rows={6}
                    value={argsDraft}
                    onChange={(e) => setArgsDraft(e.target.value)}
                  />
                  <InlineError message={parseError} />
                  <div className="flex gap-2">
                    <Button onClick={submitArgsEdit}>Rerun with new args</Button>
                    <Button variant="ghost" onClick={() => setEditingIdx(null)}>
                      Cancel
                    </Button>
                  </div>
                </div>
              </Card>
            ) : null}
          </div>
        ))}
      </div>

      {summary ? (
        <Card title="Workflow output">
          <div className="space-y-3">
            <div className="prose prose-invert max-w-none rounded-xl border border-slate-800 bg-slate-950/50 p-4 text-sm">
              <ReactMarkdown remarkPlugins={[remarkGfm]}>{summary.markdown}</ReactMarkdown>
            </div>
            <div className="flex flex-wrap gap-2">
              <Button variant="secondary" onClick={() => copyText(summary.markdown)}>
                Copy Markdown
              </Button>
              <Button
                variant="secondary"
                onClick={() =>
                  downloadFile(
                    `workflow-${state.runId}.json`,
                    JSON.stringify(summary.json, null, 2),
                  )
                }
              >
                Download JSON
              </Button>
            </div>
          </div>
        </Card>
      ) : null}

      {isRunErrored && !summary ? (
        <EmptyState
          title="Run halted on error"
          description={
            state.stepStates[currentIdx]?.error?.message ?? 'A step failed. Use Rerun to retry.'
          }
        />
      ) : null}

      <ConfirmDialog
        open={pendingApproval !== null}
        title="Apply mutating step?"
        description={`This step calls a mutating tool. The change will be applied to the server immediately.`}
        confirmLabel="Apply"
        onConfirm={() => {
          if (!pendingApproval) return;
          if (pendingApproval.mode === 'run-all') {
            resolveApproval(true);
          } else {
            setPendingApproval(null);
            void doAdvance();
          }
        }}
        onCancel={() => {
          if (pendingApproval?.mode === 'run-all') {
            resolveApproval(false);
          } else {
            setPendingApproval(null);
          }
        }}
      />
    </div>
  );
}
