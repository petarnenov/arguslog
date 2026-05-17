/**
 * `arguslog_triage_loop` — 2 steps. Loads the next batch of unresolved issues, then
 * summarises them. The "summarise" step is pure computation but kept as a real step so
 * the operator can pause + inspect the batch before generating the markdown report.
 */
import type { IssueSummary } from '@arguslog/mcp-server/contract';
import { WORKFLOW_IDS } from '@arguslog/mcp-server/contract';

import { listIssues } from '../issues';
import type { WorkflowDefinition } from '../workflow-engine';

function issueLine(issue: IssueSummary): string {
  return `#${issue.id} · ${issue.title} · ${issue.level ?? 'unknown'} · ${issue.status ?? 'unknown'}`;
}

export const triageLoopDefinition: WorkflowDefinition = {
  id: WORKFLOW_IDS.TRIAGE_LOOP,
  steps: [
    {
      id: 'batch',
      label: 'Load unresolved batch',
      tool: 'list_issues',
      prepareArgs: (state) => ({
        projectId: state.inputs.projectId as number,
        status: 'unresolved',
        limit: (state.inputs.batchSize as number | undefined) ?? 10,
      }),
      run: async (args) =>
        listIssues({
          projectId: args.projectId as number,
          status: args.status as 'unresolved',
          limit: args.limit as number,
        }),
    },
    {
      id: 'summarise',
      label: 'Summarise batch',
      // Pure computation. Kept so the operator can pause + decide whether to extend the batch.
      prepareArgs: () => ({}),
      run: async (_args, state) => {
        const issues = (state.stepStates[0]?.result as IssueSummary[]) ?? [];
        return { count: issues.length };
      },
    },
  ],
  summarize: (state) => {
    const issues = (state.stepStates[0]?.result as IssueSummary[]) ?? [];
    const markdown = `# Triage loop

Loaded ${issues.length} unresolved issue(s).

${issues.map(issueLine).join('\n')}
`;
    return { markdown, json: { issues } };
  },
};
