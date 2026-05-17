import {
  AssignIssueInputSchema,
  IssueDetailSchema,
  IssueEventSchema,
  IssueSummarySchema,
  paginated,
  TriageIssueInputSchema,
} from '@arguslog/mcp-server/contract';
import type { z } from 'zod';

import { callRawTool } from './catalog';

// `list_issues` and `list_issue_events` on the api are cursor-paginated and return
// `{data, page}` envelopes (PageResponseIssueResponse / PageResponseEventResponse in
// the OpenAPI snapshot). The MCP tool forwards the envelope verbatim — we extract
// `.data` here so existing array-shaped consumers (IssuesScreen.issuesQuery.data.map,
// eventsQuery.data[0]) stay untouched. Pagination metadata is discarded for now;
// the screen has no cursor UI yet, and wiring that is a separate change.
const PaginatedIssueSummarySchema = paginated(IssueSummarySchema);
const PaginatedIssueEventSchema = paginated(IssueEventSchema);

export async function listIssues(args: Record<string, unknown>) {
  return PaginatedIssueSummarySchema.parse(await callRawTool('list_issues', args)).data;
}

export async function getIssue(projectId: number, issueId: number) {
  return IssueDetailSchema.parse(await callRawTool('get_issue', { projectId, issueId }));
}

export async function listIssueEvents(projectId: number, issueId: number, limit = 5) {
  return PaginatedIssueEventSchema.parse(
    await callRawTool('list_issue_events', { projectId, issueId, limit }),
  ).data;
}

export async function triageIssue(input: z.input<typeof TriageIssueInputSchema>) {
  const parsed = TriageIssueInputSchema.parse(input);
  return IssueDetailSchema.parse(await callRawTool('triage_issue', parsed, true));
}

export async function assignIssue(input: z.input<typeof AssignIssueInputSchema>) {
  const parsed = AssignIssueInputSchema.parse(input);
  return IssueDetailSchema.parse(await callRawTool('assign_issue', parsed, true));
}
