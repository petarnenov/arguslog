/**
 * `arguslog_release_postmortem` — 3 read-only steps. Resolves the release, fetches
 * its first-seen issues, then a capped-at-10 sample of detail for the markdown
 * regression table. Same output shape as the v1 monolithic runner.
 */
import type { IssueDetail, IssueSummary, ReleaseSummary } from '@arguslog/mcp-server/contract';
import { WORKFLOW_IDS } from '@arguslog/mcp-server/contract';

import { getIssue, listIssues } from '../issues';
import { listReleases } from '../releases';
import type { WorkflowDefinition } from '../workflow-engine';

function findRelease(releases: ReleaseSummary[], version: string): ReleaseSummary {
  const match = releases.find((release) => release.version === version);
  if (!match) {
    throw new Error(`Release "${version}" was not found.`);
  }
  return match;
}

function topFrame(issue: IssueDetail): string {
  const frame = issue.latestEvent?.stacktrace?.frames?.[0];
  if (!frame) return 'unknown';
  return `${frame.filename ?? 'unknown'}:${frame.line ?? '?'} · ${frame.function ?? 'anonymous'}`;
}

export const releasePostmortemDefinition: WorkflowDefinition = {
  id: WORKFLOW_IDS.RELEASE_POSTMORTEM,
  steps: [
    {
      id: 'release',
      label: 'Resolve release version',
      tool: 'list_release',
      prepareArgs: (state) => ({
        projectId: state.inputs.projectId as number,
        version: state.inputs.version as string,
      }),
      run: async (args) => {
        const releases = await listReleases(args.projectId as number);
        return findRelease(releases, args.version as string);
      },
    },
    {
      id: 'issues',
      label: 'Load first-seen issues',
      tool: 'list_issues',
      prepareArgs: (state) => {
        const release = state.stepStates[0]?.result as ReleaseSummary | undefined;
        return {
          projectId: state.inputs.projectId as number,
          firstSeenReleaseId: release?.id,
          limit: 25,
        };
      },
      run: async (args) =>
        listIssues({
          projectId: args.projectId as number,
          firstSeenReleaseId: args.firstSeenReleaseId as number,
          limit: args.limit as number,
        }),
    },
    {
      id: 'details',
      label: 'Load issue detail sample (max 10)',
      tool: 'get_issue',
      prepareArgs: (state) => {
        const issues = (state.stepStates[1]?.result as IssueSummary[]) ?? [];
        return {
          projectId: state.inputs.projectId as number,
          issueIds: issues.slice(0, 10).map((i) => i.id),
        };
      },
      run: async (args) => {
        const ids = args.issueIds as number[];
        const projectId = args.projectId as number;
        return Promise.all(ids.map((id) => getIssue(projectId, id)));
      },
    },
  ],
  summarize: (state) => {
    const release = state.stepStates[0]?.result as ReleaseSummary | undefined;
    const issues = (state.stepStates[1]?.result as IssueSummary[]) ?? [];
    const detail = (state.stepStates[2]?.result as IssueDetail[]) ?? [];
    if (!release) {
      return { markdown: '# Postmortem\n\n_Run incomplete._', json: {} };
    }
    const grouped: Record<string, IssueDetail[]> = {};
    for (const issue of detail) {
      const frame = topFrame(issue);
      grouped[frame] ??= [];
      grouped[frame].push(issue);
    }
    const markdown = `# Postmortem — ${release.version}

**Issues introduced:** ${issues.length}

## Top regressions
${Object.entries(grouped)
  .map(([frame, groupedIssues]) => `- **${frame}** — ${(groupedIssues ?? []).length} issue(s)`)
  .join('\n')}
`;
    return { markdown, json: { release, issues, grouped } };
  },
};
