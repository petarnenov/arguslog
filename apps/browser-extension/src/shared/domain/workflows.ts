import type { IssueDetail, IssueSummary, ReleaseSummary } from '@arguslog/mcp-server/contract';

import { getIssue, listIssueEvents, listIssues } from './issues';
import { listReleases } from './releases';

export interface WorkflowStep {
  id: string;
  label: string;
  status: 'done' | 'error';
  detail?: string;
}

export interface WorkflowResult {
  steps: WorkflowStep[];
  markdown: string;
  json: Record<string, unknown>;
}

function topFrame(issue: IssueDetail): string {
  const frame = issue.latestEvent?.stacktrace?.frames?.[0];
  if (!frame) return 'unknown';
  return `${frame.filename ?? 'unknown'}:${frame.line ?? '?'} · ${frame.function ?? 'anonymous'}`;
}

function issueLine(issue: IssueSummary): string {
  return `#${issue.id} · ${issue.title} · ${issue.level ?? 'unknown'} · ${issue.status ?? 'unknown'}`;
}

export async function runInvestigateIssueWorkflow(projectId: number, issueId: number): Promise<WorkflowResult> {
  const issue = await getIssue(projectId, issueId);
  const events = await listIssueEvents(projectId, issueId, 5);
  const latestEvent = events[0];
  const frame = topFrame(issue);
  const markdown = `# Investigate issue #${issue.id}

- **Title:** ${issue.title}
- **Status:** ${issue.status ?? 'unknown'}
- **Occurrences:** ${issue.count ?? 0}
- **Top frame:** ${frame}
- **Latest event:** ${latestEvent?.message ?? latestEvent?.title ?? 'n/a'}

## Evidence

Loaded ${events.length} recent events and inspected the latest stack frame for a root-cause hypothesis.
`;

  return {
    steps: [
      { id: 'detail', label: 'Loaded issue detail', status: 'done' },
      { id: 'events', label: 'Loaded recent events', status: 'done' },
    ],
    markdown,
    json: { issue, events },
  };
}

function findRelease(releases: ReleaseSummary[], version: string): ReleaseSummary {
  const match = releases.find((release) => release.version === version);
  if (!match) {
    throw new Error(`Release "${version}" was not found.`);
  }
  return match;
}

export async function runRegressionCheckWorkflow(
  projectId: number,
  currentVersion: string,
  previousVersion: string,
): Promise<WorkflowResult> {
  const releases = await listReleases(projectId);
  const current = findRelease(releases, currentVersion);
  const previous = findRelease(releases, previousVersion);
  const newIssues = await listIssues({ projectId, firstSeenReleaseId: current.id, limit: 50 });
  const previousWindow = await listIssues({ projectId, seenInReleaseId: previous.id, limit: 100 });
  const previousIds = new Set(previousWindow.map((issue) => issue.id));
  const classified = newIssues.map((issue) => ({
    issue,
    status: previousIds.has(issue.id) ? 'SPIKING' : 'NEW',
  }));
  const markdown = `# Regression check — ${previousVersion} → ${currentVersion}

| Issue | Status |
|---|---|
${classified.map((item) => `| ${issueLine(item.issue)} | ${item.status} |`).join('\n')}
`;

  return {
    steps: [
      { id: 'releases', label: 'Resolved releases', status: 'done' },
      { id: 'issues', label: 'Loaded release windows', status: 'done' },
    ],
    markdown,
    json: { current, previous, newIssues, previousWindow, classified },
  };
}

export async function runReleasePostmortemWorkflow(
  projectId: number,
  version: string,
): Promise<WorkflowResult> {
  const releases = await listReleases(projectId);
  const release = findRelease(releases, version);
  const issues = await listIssues({ projectId, firstSeenReleaseId: release.id, limit: 25 });
  const detail = await Promise.all(issues.slice(0, 10).map((issue) => getIssue(projectId, issue.id)));
  const grouped = detail.reduce<Record<string, IssueDetail[]>>((acc, issue) => {
    const frame = topFrame(issue);
    acc[frame] ??= [];
    acc[frame].push(issue);
    return acc;
  }, {});

  const markdown = `# Postmortem — ${version}

**Issues introduced:** ${issues.length}

## Top regressions
${Object.entries(grouped)
  .map(([frame, groupedIssues]) => `- **${frame}** — ${(groupedIssues ?? []).length} issue(s)`)
  .join('\n')}
`;

  return {
    steps: [
      { id: 'release', label: 'Resolved release version', status: 'done' },
      { id: 'issues', label: 'Loaded first-seen issues', status: 'done' },
      { id: 'details', label: 'Loaded issue detail sample', status: 'done' },
    ],
    markdown,
    json: { release, issues, grouped },
  };
}

export async function runTriageLoopWorkflow(projectId: number, batchSize: number): Promise<WorkflowResult> {
  const issues = await listIssues({
    projectId,
    status: 'unresolved',
    limit: batchSize,
  });

  const markdown = `# Triage loop

Loaded ${issues.length} unresolved issue(s).

${issues.map(issueLine).join('\n')}
`;

  return {
    steps: [{ id: 'batch', label: 'Loaded unresolved batch', status: 'done' }],
    markdown,
    json: { issues },
  };
}
