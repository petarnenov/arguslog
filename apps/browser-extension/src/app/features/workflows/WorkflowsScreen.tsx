import { WORKFLOW_IDS } from '@arguslog/mcp-server/contract';
import { useMutation, useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';

import { getConnectionStatus } from '../../../shared/domain/connection';
import {
  runInvestigateIssueWorkflow,
  runRegressionCheckWorkflow,
  runReleasePostmortemWorkflow,
  runTriageLoopWorkflow,
  type WorkflowResult,
} from '../../../shared/domain/workflows';
import { useFeatureAvailability } from '../../../shared/hooks/useFeatureAvailability';
import {
  Badge,
  Button,
  Card,
  EmptyState,
  Input,
  Page,
} from '../../../shared/ui/components/primitives';
import { copyText, downloadFile } from '../../../shared/utils/export';
import { formatMissingTools } from '../../../shared/utils/format-missing-tools';

function WorkflowResultCard(props: { result: WorkflowResult | undefined }) {
  const result = props.result;
  if (!result) {
    return (
      <EmptyState
        title="No workflow executed yet"
        description="Run one of the curated workflows to inspect its Markdown and JSON outputs."
      />
    );
  }

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap gap-2">
        {result.steps.map((step) => (
          <Badge key={step.id} tone={step.status === 'done' ? 'success' : 'danger'}>
            {step.label}
          </Badge>
        ))}
      </div>
      <div className="prose prose-invert max-w-none rounded-xl border border-slate-800 bg-slate-950/50 p-4 text-sm">
        <ReactMarkdown remarkPlugins={[remarkGfm]}>{result.markdown}</ReactMarkdown>
      </div>
      <div className="flex flex-wrap gap-2">
        <Button variant="secondary" onClick={() => copyText(result.markdown)}>
          Copy Markdown
        </Button>
        <Button
          variant="secondary"
          onClick={() =>
            downloadFile(
              `workflow-${new Date().toISOString()}.json`,
              JSON.stringify(result.json, null, 2),
            )
          }
        >
          Download JSON
        </Button>
      </div>
    </div>
  );
}

export function WorkflowsScreen() {
  const statusQuery = useQuery({ queryKey: ['connection-status'], queryFn: getConnectionStatus });
  const projectId = statusQuery.data?.workspaceSelection.projectId;
  const issueId = statusQuery.data?.workspaceSelection.issueId;

  const investigateCaps = useFeatureAvailability(WORKFLOW_IDS.INVESTIGATE_ISSUE);
  const regressionCaps = useFeatureAvailability(WORKFLOW_IDS.REGRESSION_CHECK);
  const postmortemCaps = useFeatureAvailability(WORKFLOW_IDS.RELEASE_POSTMORTEM);
  const triageCaps = useFeatureAvailability(WORKFLOW_IDS.TRIAGE_LOOP);

  const [investigateIssueId, setInvestigateIssueId] = useState(issueId ? String(issueId) : '');
  const [currentVersion, setCurrentVersion] = useState('');
  const [previousVersion, setPreviousVersion] = useState('');
  const [postmortemVersion, setPostmortemVersion] = useState('');
  const [triageBatchSize, setTriageBatchSize] = useState('10');

  const [result, setResult] = useState<WorkflowResult | undefined>();

  const runMutation = useMutation({
    mutationFn: async (workflowId: string) => {
      if (!projectId) throw new Error('Select a project in Workspace first.');

      switch (workflowId) {
        case WORKFLOW_IDS.INVESTIGATE_ISSUE:
          return runInvestigateIssueWorkflow(projectId, Number(investigateIssueId));
        case WORKFLOW_IDS.REGRESSION_CHECK:
          return runRegressionCheckWorkflow(projectId, currentVersion, previousVersion);
        case WORKFLOW_IDS.RELEASE_POSTMORTEM:
          return runReleasePostmortemWorkflow(projectId, postmortemVersion);
        case WORKFLOW_IDS.TRIAGE_LOOP:
          return runTriageLoopWorkflow(projectId, Number(triageBatchSize || '10'));
        default:
          throw new Error(`Unsupported workflow ${workflowId}`);
      }
    },
    onSuccess: (payload) => setResult(payload),
  });

  return (
    <Page
      title="Curated workflows"
      subtitle="Native step machines for investigation, regression checks, postmortems, and triage loops."
    >
      <div className="grid gap-4 lg:grid-cols-[1fr,1fr]">
        <div className="space-y-4">
          <Card title="Investigate issue">
            <div className="space-y-3">
              <Input
                value={investigateIssueId}
                onChange={(event) => setInvestigateIssueId(event.target.value)}
                placeholder="Issue ID"
              />
              <Button
                disabled={!projectId || !investigateIssueId || !investigateCaps.available}
                onClick={() => runMutation.mutate(WORKFLOW_IDS.INVESTIGATE_ISSUE)}
                title={formatMissingTools(investigateCaps.missingTools) ?? undefined}
              >
                Run investigate
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
                  !projectId ||
                  !currentVersion ||
                  !previousVersion ||
                  !regressionCaps.available
                }
                onClick={() => runMutation.mutate(WORKFLOW_IDS.REGRESSION_CHECK)}
                title={formatMissingTools(regressionCaps.missingTools) ?? undefined}
              >
                Run regression check
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
                onClick={() => runMutation.mutate(WORKFLOW_IDS.RELEASE_POSTMORTEM)}
                title={formatMissingTools(postmortemCaps.missingTools) ?? undefined}
              >
                Build postmortem
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
                onClick={() => runMutation.mutate(WORKFLOW_IDS.TRIAGE_LOOP)}
                title={formatMissingTools(triageCaps.missingTools) ?? undefined}
              >
                Load triage batch
              </Button>
            </div>
          </Card>
        </div>

        <Card title="Workflow output">
          <WorkflowResultCard result={result} />
        </Card>
      </div>
    </Page>
  );
}
