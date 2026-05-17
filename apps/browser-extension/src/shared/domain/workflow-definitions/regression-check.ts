/**
 * `arguslog_regression_check` — 4 read-only steps. Resolves both release versions in
 * step 2 (pure computation against step 1's data), fetches each release window in
 * steps 3 + 4. The summary classifies NEW vs SPIKING using a Set built from the
 * previous window. Same markdown + JSON shape the v1 monolithic runner produced.
 */
import type { IssueSummary, ReleaseSummary } from '@arguslog/mcp-server/contract';
import { WORKFLOW_IDS } from '@arguslog/mcp-server/contract';

import { listIssues } from '../issues';
import { listReleases } from '../releases';
import type { WorkflowDefinition } from '../workflow-engine';

function findRelease(releases: ReleaseSummary[], version: string): ReleaseSummary {
  const match = releases.find((release) => release.version === version);
  if (!match) {
    throw new Error(`Release "${version}" was not found.`);
  }
  return match;
}

function issueLine(issue: IssueSummary): string {
  return `#${issue.id} · ${issue.title} · ${issue.level ?? 'unknown'} · ${issue.status ?? 'unknown'}`;
}

export const regressionCheckDefinition: WorkflowDefinition = {
  id: WORKFLOW_IDS.REGRESSION_CHECK,
  steps: [
    {
      id: 'releases',
      label: 'List releases',
      tool: 'list_release',
      prepareArgs: (state) => ({ projectId: state.inputs.projectId as number }),
      run: async (args) => listReleases(args.projectId as number),
    },
    {
      id: 'resolve',
      label: 'Resolve current + previous versions',
      // Pure computation — no tool. Throws if either version is missing → engine marks 'error'.
      prepareArgs: (state) => ({
        currentVersion: state.inputs.currentVersion as string,
        previousVersion: state.inputs.previousVersion as string,
      }),
      run: async (args, state) => {
        const releases = state.stepStates[0]?.result as ReleaseSummary[];
        const current = findRelease(releases, args.currentVersion as string);
        const previous = findRelease(releases, args.previousVersion as string);
        return { current, previous };
      },
    },
    {
      id: 'new-issues',
      label: 'Load issues first-seen in current release',
      tool: 'list_issues',
      prepareArgs: (state) => {
        const resolved = state.stepStates[1]?.result as
          | {
              current: ReleaseSummary;
            }
          | undefined;
        return {
          projectId: state.inputs.projectId as number,
          firstSeenReleaseId: resolved?.current.id,
          limit: 50,
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
      id: 'previous-window',
      label: 'Load issues seen in previous release',
      tool: 'list_issues',
      prepareArgs: (state) => {
        const resolved = state.stepStates[1]?.result as
          | {
              previous: ReleaseSummary;
            }
          | undefined;
        return {
          projectId: state.inputs.projectId as number,
          seenInReleaseId: resolved?.previous.id,
          limit: 100,
        };
      },
      run: async (args) =>
        listIssues({
          projectId: args.projectId as number,
          seenInReleaseId: args.seenInReleaseId as number,
          limit: args.limit as number,
        }),
    },
  ],
  summarize: (state) => {
    const resolved = state.stepStates[1]?.result as
      | { current: ReleaseSummary; previous: ReleaseSummary }
      | undefined;
    const newIssues = (state.stepStates[2]?.result as IssueSummary[]) ?? [];
    const previousWindow = (state.stepStates[3]?.result as IssueSummary[]) ?? [];
    if (!resolved) {
      return { markdown: '# Regression check\n\n_Run incomplete._', json: {} };
    }
    const previousIds = new Set(previousWindow.map((i) => i.id));
    const classified = newIssues.map((issue) => ({
      issue,
      status: previousIds.has(issue.id) ? 'SPIKING' : 'NEW',
    }));
    const markdown = `# Regression check — ${resolved.previous.version} → ${resolved.current.version}

| Issue | Status |
|---|---|
${classified.map((item) => `| ${issueLine(item.issue)} | ${item.status} |`).join('\n')}
`;
    return {
      markdown,
      json: {
        current: resolved.current,
        previous: resolved.previous,
        newIssues,
        previousWindow,
        classified,
      },
    };
  },
};
