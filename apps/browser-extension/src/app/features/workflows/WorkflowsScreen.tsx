/**
 * WorkflowsScreen — Phase 3 two-mode layout.
 *
 * Mode A (no active run): the original 4-card launcher. Each „Run" button starts a new
 * run via `startRun` + `saveRun`, then the screen flips into Mode B on the next query
 * tick. Capability gating from Phase 2 still applies — `useFeatureAvailability` gates
 * the launcher button per workflow.
 *
 * Mode B (active run): renders `<StepRunner />` against the persisted run state. The
 * launcher disappears (one run at a time per AC1). Abort returns to Mode A.
 *
 * The active-run state lives in `browser.storage.session` via `getActiveRun()` /
 * `saveRun()` so a side-panel close + reopen restores at the same paused step (AC3).
 */
import { WORKFLOW_IDS, type WorkflowId } from '@arguslog/mcp-server/contract';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';

import { getConnectionStatus } from '../../../shared/domain/connection';
import { WORKFLOW_DEFINITIONS } from '../../../shared/domain/workflow-definitions';
import {
  clearRun,
  getActiveRun,
  saveRun,
  startRun,
} from '../../../shared/domain/workflow-run';
import { useFeatureAvailability } from '../../../shared/hooks/useFeatureAvailability';
import {
  Button,
  Card,
  Input,
  Page,
} from '../../../shared/ui/components/primitives';
import { formatMissingTools } from '../../../shared/utils/format-missing-tools';
import { StepRunner } from './StepRunner';

const ACTIVE_RUN_QUERY_KEY = ['workflow-active-run'] as const;

export function WorkflowsScreen() {
  const queryClient = useQueryClient();
  const statusQuery = useQuery({ queryKey: ['connection-status'], queryFn: getConnectionStatus });
  const activeRunQuery = useQuery({
    queryKey: ACTIVE_RUN_QUERY_KEY,
    queryFn: getActiveRun,
  });

  const projectId = statusQuery.data?.workspaceSelection.projectId;
  const issueId = statusQuery.data?.workspaceSelection.issueId;
  const advertisedTools = new Set(statusQuery.data?.capabilitySnapshot?.toolNames ?? []);

  const investigateCaps = useFeatureAvailability(WORKFLOW_IDS.INVESTIGATE_ISSUE);
  const regressionCaps = useFeatureAvailability(WORKFLOW_IDS.REGRESSION_CHECK);
  const postmortemCaps = useFeatureAvailability(WORKFLOW_IDS.RELEASE_POSTMORTEM);
  const triageCaps = useFeatureAvailability(WORKFLOW_IDS.TRIAGE_LOOP);

  const [investigateIssueId, setInvestigateIssueId] = useState(issueId ? String(issueId) : '');
  const [currentVersion, setCurrentVersion] = useState('');
  const [previousVersion, setPreviousVersion] = useState('');
  const [postmortemVersion, setPostmortemVersion] = useState('');
  const [triageBatchSize, setTriageBatchSize] = useState('10');

  const startMutation = useMutation({
    mutationFn: async (args: { workflowId: WorkflowId; inputs: Record<string, unknown> }) => {
      const def = WORKFLOW_DEFINITIONS[args.workflowId];
      const fresh = startRun(def, args.inputs);
      await saveRun(fresh);
      return fresh;
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ACTIVE_RUN_QUERY_KEY });
    },
  });

  const abortMutation = useMutation({
    mutationFn: async () => {
      await clearRun();
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ACTIVE_RUN_QUERY_KEY });
    },
  });

  const activeRun = activeRunQuery.data ?? undefined;

  // Mode B — active run takes over the screen.
  if (activeRun) {
    const def = WORKFLOW_DEFINITIONS[activeRun.workflowId as WorkflowId];
    if (def) {
      return (
        <Page
          title={`Workflow run: ${activeRun.workflowId}`}
          subtitle={`Paused at step ${Math.min(activeRun.currentStepIndex + 1, def.steps.length)} of ${def.steps.length}.`}
        >
          <StepRunner
            def={def}
            state={activeRun}
            advertisedTools={advertisedTools}
            onStateChange={(next) => {
              queryClient.setQueryData(ACTIVE_RUN_QUERY_KEY, next);
            }}
            onAbort={() => abortMutation.mutate()}
          />
        </Page>
      );
    }
    // Unknown workflow id (post-rename / schema drift) — fall back to the launcher.
  }

  // Mode A — launcher.
  return (
    <Page
      title="Curated workflows"
      subtitle="Step-by-step investigation, regression checks, postmortems, and triage loops."
    >
      <div className="grid gap-4 lg:grid-cols-2">
        <Card title="Investigate issue">
          <div className="space-y-3">
            <Input
              value={investigateIssueId}
              onChange={(event) => setInvestigateIssueId(event.target.value)}
              placeholder="Issue ID"
            />
            <Button
              disabled={!projectId || !investigateIssueId || !investigateCaps.available}
              onClick={() =>
                startMutation.mutate({
                  workflowId: WORKFLOW_IDS.INVESTIGATE_ISSUE,
                  inputs: { projectId, issueId: Number(investigateIssueId) },
                })
              }
              title={formatMissingTools(investigateCaps.missingTools) ?? undefined}
            >
              Start investigate
            </Button>
          </div>
        </Card>

        <Card title="Regression check">
          <div className="space-y-3">
            <Input
              value={currentVersion}
              onChange={(event) => setCurrentVersion(event.target.value)}
              placeholder="Current version"
            />
            <Input
              value={previousVersion}
              onChange={(event) => setPreviousVersion(event.target.value)}
              placeholder="Previous version"
            />
            <Button
              disabled={
                !projectId || !currentVersion || !previousVersion || !regressionCaps.available
              }
              onClick={() =>
                startMutation.mutate({
                  workflowId: WORKFLOW_IDS.REGRESSION_CHECK,
                  inputs: { projectId, currentVersion, previousVersion },
                })
              }
              title={formatMissingTools(regressionCaps.missingTools) ?? undefined}
            >
              Start regression check
            </Button>
          </div>
        </Card>

        <Card title="Release postmortem">
          <div className="space-y-3">
            <Input
              value={postmortemVersion}
              onChange={(event) => setPostmortemVersion(event.target.value)}
              placeholder="Release version"
            />
            <Button
              disabled={!projectId || !postmortemVersion || !postmortemCaps.available}
              onClick={() =>
                startMutation.mutate({
                  workflowId: WORKFLOW_IDS.RELEASE_POSTMORTEM,
                  inputs: { projectId, version: postmortemVersion },
                })
              }
              title={formatMissingTools(postmortemCaps.missingTools) ?? undefined}
            >
              Start postmortem
            </Button>
          </div>
        </Card>

        <Card title="Triage loop">
          <div className="space-y-3">
            <Input
              value={triageBatchSize}
              onChange={(event) => setTriageBatchSize(event.target.value)}
              placeholder="Batch size"
            />
            <Button
              disabled={!projectId || !triageCaps.available}
              onClick={() =>
                startMutation.mutate({
                  workflowId: WORKFLOW_IDS.TRIAGE_LOOP,
                  inputs: { projectId, batchSize: Number(triageBatchSize || '10') },
                })
              }
              title={formatMissingTools(triageCaps.missingTools) ?? undefined}
            >
              Start triage loop
            </Button>
          </div>
        </Card>
      </div>
    </Page>
  );
}
