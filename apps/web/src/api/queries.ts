import { keepPreviousData, useQuery } from '@tanstack/react-query';

import { listAlertDestinations, listAlertRules } from './alerts';
import { getUsage } from './billing';
import {
  getIssue,
  listIssueEvents,
  listIssues,
  type ListIssueEventsParams,
  type ListIssuesParams,
} from './issues';
import { listDsns } from './keys';
import { listOrgMembers } from './members';
import { listMyOrgs } from './orgs';
import { listPlatforms } from './platforms';
import { listProjects } from './projects';
import { listReleases } from './releases';
import { listMyTokens } from './tokens';

export const queryKeys = {
  myOrgs: () => ['orgs', 'mine'] as const,
  projects: (orgId: number) => ['projects', orgId] as const,
  issues: (params: ListIssuesParams) => ['issues', params] as const,
  issue: (projectId: number, issueId: number) => ['issues', projectId, issueId] as const,
  issueEvents: (params: ListIssueEventsParams) => ['issueEvents', params] as const,
  alertRules: (projectId: number) => ['alert-rules', projectId] as const,
  alertDestinations: (orgId: number) => ['alert-destinations', orgId] as const,
  usage: (orgId: number) => ['usage', orgId] as const,
  myTokens: () => ['tokens', 'mine'] as const,
  orgMembers: (orgId: number) => ['org-members', orgId] as const,
  platforms: () => ['platforms'] as const,
  releases: (projectId: number) => ['releases', projectId] as const,
  dsns: (projectId: number) => ['dsns', projectId] as const,
};

export function useMyOrgs(options: { enabled?: boolean } = {}) {
  return useQuery({
    queryKey: queryKeys.myOrgs(),
    queryFn: listMyOrgs,
    enabled: options.enabled ?? true,
    staleTime: 60_000,
  });
}

export function useProjects(orgId: number | undefined, options: { enabled?: boolean } = {}) {
  return useQuery({
    queryKey: queryKeys.projects(orgId ?? -1),
    queryFn: () => listProjects(orgId as number),
    enabled: (options.enabled ?? true) && orgId != null,
    staleTime: 30_000,
  });
}

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

export function useIssue(projectId: number, issueId: number, options: { enabled?: boolean } = {}) {
  return useQuery({
    queryKey: queryKeys.issue(projectId, issueId),
    queryFn: () => getIssue(projectId, issueId),
    enabled: options.enabled ?? true,
  });
}

export function useIssueEvents(params: ListIssueEventsParams, options: { enabled?: boolean } = {}) {
  return useQuery({
    queryKey: queryKeys.issueEvents(params),
    queryFn: () => listIssueEvents(params),
    enabled: options.enabled ?? true,
    placeholderData: keepPreviousData,
    staleTime: 15_000,
  });
}

export function useAlertRules(projectId: number | undefined, options: { enabled?: boolean } = {}) {
  return useQuery({
    queryKey: queryKeys.alertRules(projectId ?? -1),
    queryFn: () => listAlertRules(projectId as number),
    enabled: (options.enabled ?? true) && projectId != null,
    staleTime: 30_000,
  });
}

export function useAlertDestinations(
  orgId: number | undefined,
  options: { enabled?: boolean } = {},
) {
  return useQuery({
    queryKey: queryKeys.alertDestinations(orgId ?? -1),
    queryFn: () => listAlertDestinations(orgId as number),
    enabled: (options.enabled ?? true) && orgId != null,
    staleTime: 30_000,
  });
}

export function useMyTokens(options: { enabled?: boolean } = {}) {
  return useQuery({
    queryKey: queryKeys.myTokens(),
    queryFn: listMyTokens,
    enabled: options.enabled ?? true,
    staleTime: 30_000,
  });
}

export function usePlatforms(options: { enabled?: boolean } = {}) {
  return useQuery({
    queryKey: queryKeys.platforms(),
    queryFn: listPlatforms,
    enabled: options.enabled ?? true,
    // Catalog is essentially static — refetch sparingly. Updated via DB UPDATE on new SDK release.
    staleTime: 5 * 60_000,
  });
}

export function useOrgMembers(orgId: number | undefined, options: { enabled?: boolean } = {}) {
  return useQuery({
    queryKey: queryKeys.orgMembers(orgId ?? -1),
    queryFn: () => listOrgMembers(orgId as number),
    enabled: (options.enabled ?? true) && orgId != null,
    staleTime: 30_000,
  });
}

export function useReleases(projectId: number | undefined, options: { enabled?: boolean } = {}) {
  return useQuery({
    queryKey: queryKeys.releases(projectId ?? -1),
    queryFn: () => listReleases(projectId as number),
    enabled: (options.enabled ?? true) && projectId != null,
    staleTime: 30_000,
  });
}

export function useDsns(projectId: number | undefined, options: { enabled?: boolean } = {}) {
  return useQuery({
    queryKey: queryKeys.dsns(projectId ?? -1),
    queryFn: () => listDsns(projectId as number),
    enabled: (options.enabled ?? true) && projectId != null,
    staleTime: 30_000,
  });
}

export function useUsage(orgId: number | undefined, options: { enabled?: boolean } = {}) {
  return useQuery({
    queryKey: queryKeys.usage(orgId ?? -1),
    queryFn: () => getUsage(orgId as number),
    enabled: (options.enabled ?? true) && orgId != null,
    // 60s — usage moves slowly and the API doc commits to a once-per-minute poll.
    staleTime: 60_000,
  });
}
