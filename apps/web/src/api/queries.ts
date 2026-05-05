import { keepPreviousData, useQuery } from '@tanstack/react-query';

import {
  getIssue,
  listIssueEvents,
  listIssues,
  type ListIssueEventsParams,
  type ListIssuesParams,
} from './issues';

export const queryKeys = {
  issues: (params: ListIssuesParams) => ['issues', params] as const,
  issue: (projectId: number, issueId: number) => ['issues', projectId, issueId] as const,
  issueEvents: (params: ListIssueEventsParams) => ['issueEvents', params] as const,
};

export function useIssues(params: ListIssuesParams, options: { enabled?: boolean } = {}) {
  return useQuery({
    queryKey: queryKeys.issues(params),
    queryFn: () => listIssues(params),
    enabled: options.enabled ?? true,
    // keepPreviousData stops the table from clearing while the next page loads —
    // matters for cursor pagination where the user expects continuity.
    placeholderData: keepPreviousData,
    staleTime: 15_000,
  });
}

export function useIssue(projectId: number, issueId: number) {
  return useQuery({
    queryKey: queryKeys.issue(projectId, issueId),
    queryFn: () => getIssue(projectId, issueId),
  });
}

export function useIssueEvents(params: ListIssueEventsParams) {
  return useQuery({
    queryKey: queryKeys.issueEvents(params),
    queryFn: () => listIssueEvents(params),
    placeholderData: keepPreviousData,
    staleTime: 15_000,
  });
}
