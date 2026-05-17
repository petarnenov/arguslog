import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { getConnectionStatus } from '../../../shared/domain/connection';
import { useI18n } from '../../../shared/hooks/useI18n';
import {
  assignIssue,
  getIssue,
  listIssueEvents,
  listIssues,
  triageIssue,
} from '../../../shared/domain/issues';
import { listMembers } from '../../../shared/domain/workspace';
import { useFeatureAvailability } from '../../../shared/hooks/useFeatureAvailability';
import { ConfirmDialog } from '../../../shared/ui/components/ConfirmDialog';
import { DashboardLink } from '../../../shared/ui/components/DashboardLink';
import {
  Badge,
  Button,
  Card,
  EmptyState,
  Input,
  Page,
  Select,
  Textarea,
} from '../../../shared/ui/components/primitives';
import { buildIssueUrl, getDashboardBaseUrl } from '../../../shared/utils/dashboard-url';
import { copyText } from '../../../shared/utils/export';
import { formatMissingTools } from '../../../shared/utils/format-missing-tools';

export function IssuesScreen() {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const { t } = useI18n();
  const statusQuery = useQuery({ queryKey: ['connection-status'], queryFn: getConnectionStatus });
  const projectId = statusQuery.data?.workspaceSelection.projectId;
  const orgId = statusQuery.data?.workspaceSelection.orgId;
  const orgSlug = statusQuery.data?.workspaceSelection.orgSlug;
  const dashboardBase = statusQuery.data
    ? getDashboardBaseUrl(statusQuery.data.settings.endpoint)
    : undefined;
  const issueActions = useFeatureAvailability('issueActions');
  const issueActionsTooltip = formatMissingTools(issueActions.missingTools);

  const [filters, setFilters] = useState({ status: 'unresolved', level: '', q: '' });
  const [selectedIssueId, setSelectedIssueId] = useState<number | undefined>(
    statusQuery.data?.workspaceSelection.issueId,
  );
  const [triageStatus, setTriageStatus] = useState<'unresolved' | 'resolved' | 'ignored'>(
    'resolved',
  );
  const [assigneeId, setAssigneeId] = useState<string>('');
  const [confirmAction, setConfirmAction] = useState<'triage' | 'assign' | undefined>();

  useEffect(() => {
    if (statusQuery.data?.workspaceSelection.issueId) {
      setSelectedIssueId(statusQuery.data.workspaceSelection.issueId);
    }
  }, [statusQuery.data?.workspaceSelection.issueId]);

  const issuesQuery = useQuery({
    queryKey: ['issues', projectId, filters],
    queryFn: () =>
      listIssues({
        projectId,
        status: filters.status || undefined,
        level: filters.level || undefined,
        q: filters.q || undefined,
        limit: 25,
      }),
    enabled: Boolean(projectId),
  });

  const detailQuery = useQuery({
    queryKey: ['issue', projectId, selectedIssueId],
    queryFn: () => getIssue(projectId!, selectedIssueId!),
    enabled: Boolean(projectId && selectedIssueId),
  });

  const eventsQuery = useQuery({
    queryKey: ['issue-events', projectId, selectedIssueId],
    queryFn: () => listIssueEvents(projectId!, selectedIssueId!, 5),
    enabled: Boolean(projectId && selectedIssueId),
  });

  const membersQuery = useQuery({
    queryKey: ['members', orgId],
    queryFn: () => listMembers(orgId!),
    enabled: Boolean(orgId),
  });

  const triageMutation = useMutation({
    mutationFn: () =>
      triageIssue({
        projectId: projectId!,
        issueId: selectedIssueId!,
        body: { status: triageStatus },
      }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['issues'] });
      await queryClient.invalidateQueries({ queryKey: ['issue', projectId, selectedIssueId] });
      setConfirmAction(undefined);
    },
  });

  const assignMutation = useMutation({
    mutationFn: () =>
      assignIssue({
        projectId: projectId!,
        issueId: selectedIssueId!,
        body: { userId: assigneeId || null },
      }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['issues'] });
      await queryClient.invalidateQueries({ queryKey: ['issue', projectId, selectedIssueId] });
      setConfirmAction(undefined);
    },
  });

  const latestEventText = useMemo(() => {
    const event = eventsQuery.data?.[0];
    return JSON.stringify(event ?? {}, null, 2);
  }, [eventsQuery.data]);

  if (!projectId) {
    return (
      <Page title="Issues" subtitle="Select a project from Workspace first.">
        <EmptyState
          title="No project selected"
          description="Issue browsing and triage depend on the active project context."
        />
        <div className="mt-3 flex justify-center">
          <Button onClick={() => navigate('/workspace')} data-testid="issues-pick-project-cta">
            {t('btnPickProject')}
          </Button>
        </div>
      </Page>
    );
  }

  return (
    <Page title="Issues" subtitle={`Browse, inspect, and mutate issues for project ${projectId}.`}>
      <Card title="Filters">
        <div className="grid gap-3 md:grid-cols-3">
          <Select
            value={filters.status}
            onChange={(event) =>
              setFilters((current) => ({ ...current, status: event.target.value }))
            }
          >
            <option value="unresolved">Unresolved</option>
            <option value="resolved">Resolved</option>
            <option value="ignored">Ignored</option>
          </Select>
          <Select
            value={filters.level}
            onChange={(event) =>
              setFilters((current) => ({ ...current, level: event.target.value }))
            }
          >
            <option value="">All levels</option>
            <option value="fatal">Fatal</option>
            <option value="error">Error</option>
            <option value="warning">Warning</option>
            <option value="info">Info</option>
            <option value="debug">Debug</option>
          </Select>
          <Input
            value={filters.q}
            onChange={(event) => setFilters((current) => ({ ...current, q: event.target.value }))}
            placeholder="Search title or culprit"
          />
        </div>
      </Card>

      <div className="grid gap-4 lg:grid-cols-[1.1fr,0.9fr]">
        <Card title="Issue list">
          {issuesQuery.error ? (
            // An empty list under the "Issue list" card silently swallows the most common
            // failure mode (401 from an expired/revoked PAT) — surface the error so the
            // operator can act instead of squinting at a blank panel.
            <div
              className="mb-3 rounded-xl border border-rose-500/40 bg-rose-500/10 p-3 text-sm text-rose-200"
              data-testid="issues-error-banner"
              role="alert"
            >
              <p className="font-medium">{t('errIssuesLoadFailed')}</p>
              <p className="mt-1 text-rose-300/90">
                {issuesQuery.error instanceof Error
                  ? issuesQuery.error.message
                  : 'Unknown error — see Settings → Diagnostics for details.'}
              </p>
            </div>
          ) : null}
          <div className="space-y-2">
            {issuesQuery.data?.map((issue) => (
              <button
                key={issue.id}
                type="button"
                onClick={() => setSelectedIssueId(issue.id)}
                className={`w-full rounded-xl border p-3 text-left transition ${
                  issue.id === selectedIssueId
                    ? 'border-blue-400 bg-blue-500/10'
                    : 'border-slate-800 bg-slate-950/40 hover:border-slate-600'
                }`}
              >
                <div className="flex items-center justify-between gap-3">
                  <div>
                    <p className="font-medium text-white">
                      #{issue.id} · {issue.title}
                    </p>
                    <p className="mt-1 text-sm text-slate-400">{issue.culprit ?? 'No culprit'}</p>
                  </div>
                  <div className="flex flex-col items-end gap-2">
                    <div className="flex items-center gap-1">
                      <Badge>{issue.level ?? 'n/a'}</Badge>
                      {dashboardBase && orgSlug && projectId ? (
                        <DashboardLink
                          href={buildIssueUrl(dashboardBase, orgSlug, projectId, issue.id)}
                        />
                      ) : null}
                    </div>
                    <span className="text-xs text-slate-500">{issue.status ?? 'unknown'}</span>
                  </div>
                </div>
              </button>
            ))}
            {!issuesQuery.data?.length ? (
              <EmptyState
                title="No issues found"
                description="Adjust filters or wait for new issue traffic."
              />
            ) : null}
          </div>
        </Card>

        <Card title={detailQuery.data ? `Issue #${detailQuery.data.id}` : 'Issue detail'}>
          {detailQuery.data ? (
            <div className="space-y-4">
              <div>
                <p className="text-base font-semibold text-white">{detailQuery.data.title}</p>
                <div className="mt-2 flex flex-wrap gap-2">
                  <Badge>{detailQuery.data.status ?? 'unknown'}</Badge>
                  <Badge>{detailQuery.data.level ?? 'unknown'}</Badge>
                </div>
              </div>

              <div className="grid gap-2 text-sm text-slate-300">
                <p>Occurrences: {detailQuery.data.count ?? 0}</p>
                <p>Assignee: {detailQuery.data.assigneeUserId ?? 'Unassigned'}</p>
                <p>Fingerprint: {detailQuery.data.fingerprint?.join(', ') ?? 'n/a'}</p>
              </div>

              <div className="grid gap-3 md:grid-cols-2">
                <div className="space-y-2">
                  <label
                    htmlFor="triage-status"
                    className="text-xs uppercase tracking-wide text-slate-400"
                  >
                    Triage status
                  </label>
                  <Select
                    id="triage-status"
                    value={triageStatus}
                    onChange={(event) =>
                      setTriageStatus(event.target.value as 'unresolved' | 'resolved' | 'ignored')
                    }
                  >
                    <option value="resolved">Resolved</option>
                    <option value="ignored">Ignored</option>
                    <option value="unresolved">Unresolved</option>
                  </Select>
                  <Button
                    variant="secondary"
                    onClick={() => setConfirmAction('triage')}
                    disabled={!issueActions.available}
                    title={issueActionsTooltip ?? undefined}
                  >
                    Triage issue
                  </Button>
                </div>

                <div className="space-y-2">
                  <label
                    htmlFor="issue-assignee"
                    className="text-xs uppercase tracking-wide text-slate-400"
                  >
                    Assign to member
                  </label>
                  <Select
                    id="issue-assignee"
                    value={assigneeId}
                    onChange={(event) => setAssigneeId(event.target.value)}
                  >
                    <option value="">Unassign</option>
                    {membersQuery.data?.map((member) => (
                      <option key={member.userId ?? member.email} value={member.userId ?? ''}>
                        {member.displayName ?? member.email ?? member.userId}
                      </option>
                    ))}
                  </Select>
                  <Button
                    variant="secondary"
                    onClick={() => setConfirmAction('assign')}
                    disabled={!issueActions.available}
                    title={issueActionsTooltip ?? undefined}
                  >
                    Assign issue
                  </Button>
                </div>
              </div>

              <div className="space-y-2">
                <div className="flex items-center justify-between">
                  <p className="text-xs uppercase tracking-wide text-slate-400">
                    Recent event payload
                  </p>
                  <Button variant="ghost" onClick={() => copyText(latestEventText)}>
                    Copy JSON
                  </Button>
                </div>
                <Textarea
                  aria-label="Recent event payload"
                  readOnly
                  rows={14}
                  value={latestEventText}
                />
              </div>
            </div>
          ) : (
            <EmptyState
              title="No issue selected"
              description="Pick an issue from the list to inspect details and recent events."
            />
          )}
        </Card>
      </div>

      <ConfirmDialog
        open={confirmAction === 'triage'}
        title="Confirm triage change"
        description={`Set issue #${selectedIssueId} to ${triageStatus}? Writes are never auto-retried.`}
        onCancel={() => setConfirmAction(undefined)}
        onConfirm={() => triageMutation.mutate()}
      />

      <ConfirmDialog
        open={confirmAction === 'assign'}
        title="Confirm assignee change"
        description={`Assign issue #${selectedIssueId} to ${assigneeId || 'nobody'}?`}
        onCancel={() => setConfirmAction(undefined)}
        onConfirm={() => assignMutation.mutate()}
      />
    </Page>
  );
}
