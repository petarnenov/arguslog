import { apiFetch, buildQuery } from './client';

export type IssueStatus = 'unresolved' | 'resolved' | 'ignored';
export type IssueLevel = 'fatal' | 'error' | 'warning' | 'info' | 'debug';

export interface Issue {
  id: number;
  projectId: number;
  fingerprint: string;
  status: IssueStatus;
  level: IssueLevel;
  title: string;
  culprit: string | null;
  firstSeenAt: string;
  lastSeenAt: string;
  occurrenceCount: number;
  /** UUID of the assigned org member, or null when unassigned. */
  assigneeUserId: string | null;
}

export interface PageMeta {
  next?: string;
}

export interface PageResponse<T> {
  data: T[];
  page: PageMeta;
}

export interface ListIssuesParams {
  projectId: number;
  status?: IssueStatus;
  level?: IssueLevel;
  cursor?: string;
  limit?: number;
}

export function listIssues({
  projectId,
  status,
  level,
  cursor,
  limit,
}: ListIssuesParams): Promise<PageResponse<Issue>> {
  const qs = buildQuery({ status, level, cursor, limit });
  return apiFetch<PageResponse<Issue>>(`/api/v1/projects/${projectId}/issues${qs}`);
}

export interface ListIssueEventsParams {
  projectId: number;
  issueId: number;
  cursor?: string;
  limit?: number;
}

export interface IssueEvent {
  id: string;
  issueId: number;
  projectId: number;
  receivedAt: string;
  payload: unknown;
}

export function getIssue(projectId: number, issueId: number): Promise<Issue> {
  return apiFetch<Issue>(`/api/v1/projects/${projectId}/issues/${issueId}`);
}

export function listIssueEvents({
  projectId,
  issueId,
  cursor,
  limit,
}: ListIssueEventsParams): Promise<PageResponse<IssueEvent>> {
  const qs = buildQuery({ cursor, limit });
  return apiFetch<PageResponse<IssueEvent>>(
    `/api/v1/projects/${projectId}/issues/${issueId}/events${qs}`,
  );
}

/** Resolve, ignore, or reopen an issue. Any org member may call. */
export function updateIssueStatus(
  projectId: number,
  issueId: number,
  status: IssueStatus,
): Promise<Issue> {
  return apiFetch<Issue>(`/api/v1/projects/${projectId}/issues/${issueId}`, {
    method: 'PATCH',
    body: JSON.stringify({ status }),
  });
}

/** Assign an issue to a user, or pass {@code null} to unassign. Assignee must be an org member. */
export function updateIssueAssignee(
  projectId: number,
  issueId: number,
  userId: string | null,
): Promise<Issue> {
  return apiFetch<Issue>(`/api/v1/projects/${projectId}/issues/${issueId}/assignee`, {
    method: 'PATCH',
    body: JSON.stringify({ userId }),
  });
}
