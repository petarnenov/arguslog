import {
  AssignIssueInputSchema,
  IssueDetailSchema,
  IssueEventSchema,
  IssueSummarySchema,
  TriageIssueInputSchema,
} from '@arguslog/mcp-server/contract';
import { z } from 'zod';

import { callRawTool } from './catalog';

export async function listIssues(args: Record<string, unknown>) {
  return z.array(IssueSummarySchema).parse(await callRawTool('list_issues', args));
}

export async function getIssue(projectId: number, issueId: number) {
  return IssueDetailSchema.parse(await callRawTool('get_issue', { projectId, issueId }));
}

export async function listIssueEvents(projectId: number, issueId: number, limit = 5) {
  return z
    .array(IssueEventSchema)
    .parse(await callRawTool('list_issue_events', { projectId, issueId, limit }));
}

export async function triageIssue(input: z.input<typeof TriageIssueInputSchema>) {
  const parsed = TriageIssueInputSchema.parse(input);
  return IssueDetailSchema.parse(await callRawTool('triage_issue', parsed, true));
}

export async function assignIssue(input: z.input<typeof AssignIssueInputSchema>) {
  const parsed = AssignIssueInputSchema.parse(input);
  return IssueDetailSchema.parse(await callRawTool('assign_issue', parsed, true));
}
