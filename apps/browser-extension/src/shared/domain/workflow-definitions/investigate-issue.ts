/**
 * `arguslog_investigate_issue` workflow definition — 2 read-only steps.
 *
 * Step 1 fetches the full issue detail. Step 2 fetches the latest 5 events. The
 * summary pulls the top stack frame from the issue's latest-event snapshot + builds
 * the same markdown shape the v1 `runInvestigateIssueWorkflow` produced.
 */
import type { IssueDetail, IssueEvent } from '@arguslog/mcp-server/contract';
import { WORKFLOW_IDS } from '@arguslog/mcp-server/contract';

import { getIssue, listIssueEvents } from '../issues';
import type { WorkflowDefinition } from '../workflow-engine';

function topFrame(issue: IssueDetail): string {
  const frame = issue.latestEvent?.stacktrace?.frames?.[0];
  if (!frame) return 'unknown';
  return `${frame.filename ?? 'unknown'}:${frame.line ?? '?'} · ${frame.function ?? 'anonymous'}`;
}

export const investigateIssueDefinition: WorkflowDefinition = {
  id: WORKFLOW_IDS.INVESTIGATE_ISSUE,
  steps: [
    {
      id: 'detail',
      label: 'Load issue detail',
      tool: 'get_issue',
      prepareArgs: (state) => ({
        projectId: state.inputs.projectId as number,
        issueId: state.inputs.issueId as number,
      }),
      run: async (args) => getIssue(args.projectId as number, args.issueId as number),
    },
    {
      id: 'events',
      label: 'Load recent events (limit 5)',
      tool: 'list_issue_events',
      prepareArgs: (state) => ({
        projectId: state.inputs.projectId as number,
        issueId: state.inputs.issueId as number,
        limit: (state.inputs.eventLimit as number | undefined) ?? 5,
      }),
      run: async (args) =>
        listIssueEvents(args.projectId as number, args.issueId as number, args.limit as number),
    },
  ],
  summarize: (state) => {
    const issue = state.stepStates[0]?.result as IssueDetail | undefined;
    const events = state.stepStates[1]?.result as IssueEvent[] | undefined;
    if (!issue || !events) {
      return {
        markdown: '# Investigate issue\n\n_Run incomplete._',
        json: {},
      };
    }
    const latest = events[0];
    const frame = topFrame(issue);
    const markdown = `# Investigate issue #${issue.id}

- **Title:** ${issue.title}
- **Status:** ${issue.status ?? 'unknown'}
- **Occurrences:** ${issue.count ?? 0}
- **Top frame:** ${frame}
- **Latest event:** ${latest?.message ?? latest?.title ?? 'n/a'}

## Evidence

Loaded ${events.length} recent events and inspected the latest stack frame for a root-cause hypothesis.
`;
    return { markdown, json: { issue, events } };
  },
};
