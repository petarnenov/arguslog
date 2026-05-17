import {
  Alert,
  Avatar,
  Badge,
  Breadcrumbs,
  Button,
  Card,
  Center,
  Code,
  Grid,
  Group,
  Loader,
  Menu,
  SegmentedControl,
  Stack,
  Table,
  Text,
  Title,
} from '@mantine/core';
import {
  IconCheck,
  IconChevronRight,
  IconEyeOff,
  IconRotate,
  IconUser,
  IconUserPlus,
} from '@tabler/icons-react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams, useSearchParams } from 'react-router';

import { ApiError } from '../api/client';
import {
  updateIssueAssignee,
  updateIssueStatus,
  type IssueLevel,
  type IssueStatus,
} from '../api/issues';
import { useIssue, useIssueEvents, useMyOrgs, useOrgMembers } from '../api/queries';
import { useReportSoftError } from '../lib/reportSoftError';

import {
  BreadcrumbsView,
  extractBreadcrumbs,
  type RawBreadcrumb,
} from './issue-detail/Breadcrumbs';
import {
  EventDetailsView,
  extractEventMeta,
  hasAnyMeta,
  type EventMeta,
} from './issue-detail/EventDetails';
import {
  extractFrames,
  hasSymbolication,
  type RawFrame,
  StacktraceView,
} from './issue-detail/Stacktrace';
import { extractWeb3Summary, Web3Panel, type Web3Summary } from './issue-detail/Web3Panel';

const LEVEL_COLOR: Record<IssueLevel, string> = {
  fatal: 'red',
  error: 'orange',
  warning: 'yellow',
  info: 'blue',
  debug: 'gray',
};

const STATUS_COLOR: Record<IssueStatus, string> = {
  unresolved: 'red',
  resolved: 'green',
  ignored: 'gray',
};

export function IssueDetailPage() {
  const { t, i18n } = useTranslation();
  const { orgSlug, projectId: rawProjectId, issueId: rawIssueId } = useParams();
  const [search, setSearch] = useSearchParams();

  const projectId = Number(rawProjectId);
  const issueId = Number(rawIssueId);
  const valid =
    Number.isFinite(projectId) && projectId > 0 && Number.isFinite(issueId) && issueId > 0;
  const cursor = search.get('cursor') ?? undefined;

  const issueQ = useIssue(projectId, issueId, { enabled: valid });
  const eventsQ = useIssueEvents({ projectId, issueId, cursor, limit: 25 }, { enabled: valid });

  const queryClient = useQueryClient();
  const orgsQ = useMyOrgs({ enabled: valid });
  // Defensive: tests stub the orgs query with non-array shapes; the chip simply won't show org
  // members until a real list arrives.
  const orgsList = Array.isArray(orgsQ.data) ? orgsQ.data : [];
  const currentOrg = orgsList.find((o) => o.slug === orgSlug);
  const membersQ = useOrgMembers(currentOrg?.id, { enabled: valid && !!currentOrg });
  const membersList = Array.isArray(membersQ.data) ? membersQ.data : [];
  const [triageError, setTriageError] = useState<string | null>(null);

  const statusMutation = useMutation({
    mutationFn: (status: IssueStatus) => updateIssueStatus(projectId, issueId, status),
    onSuccess: (updated) => {
      setTriageError(null);
      queryClient.setQueryData(['issues', projectId, issueId], updated);
      void queryClient.invalidateQueries({ queryKey: ['issues'] });
    },
    onError: (err) => {
      setTriageError(
        err instanceof ApiError ? (err.problem.detail ?? err.problem.title) : String(err),
      );
    },
  });

  const assigneeMutation = useMutation({
    mutationFn: (userId: string | null) => updateIssueAssignee(projectId, issueId, userId),
    onSuccess: (updated) => {
      setTriageError(null);
      queryClient.setQueryData(['issues', projectId, issueId], updated);
      void queryClient.invalidateQueries({ queryKey: ['issues'] });
    },
    onError: (err) => {
      setTriageError(
        err instanceof ApiError ? (err.problem.detail ?? err.problem.title) : String(err),
      );
    },
  });

  useReportSoftError(
    !valid,
    `IssueDetailPage: invalid params projectId="${rawProjectId}" issueId="${rawIssueId}"`,
  );

  // Per-issue toggle: when any event has decoded frames, default to "original" so the user sees
  // the symbolicated location first; the toggle lets them flip back to the bundled output for
  // when they need to verify what the SDK actually saw.
  const [preferOriginal, setPreferOriginal] = useState(true);
  const eventFrames = useMemo<Record<string, RawFrame[]>>(() => {
    const out: Record<string, RawFrame[]> = {};
    for (const ev of eventsQ.data?.data ?? []) out[ev.id] = extractFrames(ev.payload);
    return out;
  }, [eventsQ.data]);
  const eventBreadcrumbs = useMemo<Record<string, RawBreadcrumb[]>>(() => {
    const out: Record<string, RawBreadcrumb[]> = {};
    for (const ev of eventsQ.data?.data ?? []) out[ev.id] = extractBreadcrumbs(ev.payload);
    return out;
  }, [eventsQ.data]);
  const eventMetas = useMemo<Record<string, EventMeta>>(() => {
    const out: Record<string, EventMeta> = {};
    for (const ev of eventsQ.data?.data ?? []) out[ev.id] = extractEventMeta(ev.payload);
    return out;
  }, [eventsQ.data]);
  const eventWeb3 = useMemo<Record<string, Web3Summary | undefined>>(() => {
    const out: Record<string, Web3Summary | undefined> = {};
    for (const ev of eventsQ.data?.data ?? []) {
      out[ev.id] = extractWeb3Summary(eventMetas[ev.id]!, eventBreadcrumbs[ev.id] ?? []);
    }
    return out;
  }, [eventsQ.data, eventMetas, eventBreadcrumbs]);
  const anySymbolicated = useMemo(
    () => Object.values(eventFrames).some((f) => hasSymbolication(f)),
    [eventFrames],
  );

  const formatter = useMemo(
    () =>
      new Intl.DateTimeFormat(i18n.language || 'en', {
        dateStyle: 'medium',
        timeStyle: 'medium',
      }),
    [i18n.language],
  );

  const goToPage = (cursorValue: string | undefined) => {
    const next = new URLSearchParams(search);
    if (cursorValue) next.set('cursor', cursorValue);
    else next.delete('cursor');
    setSearch(next);
  };

  if (!valid) {
    return (
      <Stack>
        <Alert color="yellow">{t('issues.invalidProjectId')}</Alert>
      </Stack>
    );
  }

  if (issueQ.isLoading) {
    return (
      <Center py="xl">
        <Loader />
      </Center>
    );
  }

  if (issueQ.isError) {
    return (
      <Alert color="red" title={t('errors.generic')}>
        <Group justify="space-between">
          <Text size="sm">{(issueQ.error as Error).message}</Text>
          <Button size="xs" variant="light" onClick={() => void issueQ.refetch()}>
            {t('errors.tryAgain')}
          </Button>
        </Group>
      </Alert>
    );
  }

  const issue = issueQ.data;
  if (!issue) {
    return <Alert color="gray">{t('issues.notFound')}</Alert>;
  }

  return (
    <Stack>
      <Breadcrumbs separator={<IconChevronRight size={14} />}>
        <Link to={`/orgs/${orgSlug}/projects/${projectId}/issues`}>{t('issues.title')}</Link>
        <Text>#{issue.id}</Text>
      </Breadcrumbs>

      <Stack gap="xs">
        <Group gap="sm" justify="space-between" align="flex-start">
          <Group gap="sm">
            <Badge
              color={STATUS_COLOR[issue.status]}
              variant="light"
              data-testid="issue-status-badge"
            >
              {t(`issues.status.${issue.status}`)}
            </Badge>
            <Badge color={LEVEL_COLOR[issue.level]}>{t(`issues.level.${issue.level}`)}</Badge>
          </Group>
          <Group gap="xs">
            {issue.status !== 'resolved' && (
              <Button
                size="xs"
                color="green"
                variant="light"
                leftSection={<IconCheck size={14} />}
                onClick={() => statusMutation.mutate('resolved')}
                loading={statusMutation.isPending && statusMutation.variables === 'resolved'}
                data-testid="issue-detail-resolve"
              >
                {t('issues.actions.resolve')}
              </Button>
            )}
            {issue.status !== 'ignored' && (
              <Button
                size="xs"
                color="gray"
                variant="light"
                leftSection={<IconEyeOff size={14} />}
                onClick={() => statusMutation.mutate('ignored')}
                loading={statusMutation.isPending && statusMutation.variables === 'ignored'}
                data-testid="issue-detail-ignore"
              >
                {t('issues.actions.ignore')}
              </Button>
            )}
            {issue.status !== 'unresolved' && (
              <Button
                size="xs"
                color="red"
                variant="light"
                leftSection={<IconRotate size={14} />}
                onClick={() => statusMutation.mutate('unresolved')}
                loading={statusMutation.isPending && statusMutation.variables === 'unresolved'}
                data-testid="issue-detail-reopen"
              >
                {t('issues.actions.reopen')}
              </Button>
            )}
            <AssigneeChip
              assigneeUserId={issue.assigneeUserId}
              members={membersList}
              loading={assigneeMutation.isPending}
              onChange={(userId) => assigneeMutation.mutate(userId)}
            />
          </Group>
        </Group>
        <Title order={2}>{issue.title}</Title>
        {issue.culprit && (
          <Text c="dimmed" size="sm">
            {issue.culprit}
          </Text>
        )}
        {triageError && (
          <Alert color="red" variant="light" withCloseButton onClose={() => setTriageError(null)}>
            {triageError}
          </Alert>
        )}
      </Stack>

      <Grid>
        <Grid.Col span={{ base: 12, md: 4 }}>
          <Card withBorder padding="md">
            <Stack gap={4}>
              <Text size="xs" c="dimmed">
                {t('issueDetail.firstSeen')}
              </Text>
              <Text>{formatter.format(new Date(issue.firstSeenAt))}</Text>
              {issue.firstSeenReleaseVersion && (
                <Badge variant="light" color="grape" mt={4} data-testid="first-seen-release-badge">
                  {t('issueDetail.firstSeenInRelease', {
                    version: issue.firstSeenReleaseVersion,
                  })}
                </Badge>
              )}
            </Stack>
          </Card>
        </Grid.Col>
        <Grid.Col span={{ base: 12, md: 4 }}>
          <Card withBorder padding="md">
            <Stack gap={4}>
              <Text size="xs" c="dimmed">
                {t('issueDetail.lastSeen')}
              </Text>
              <Text>{formatter.format(new Date(issue.lastSeenAt))}</Text>
            </Stack>
          </Card>
        </Grid.Col>
        <Grid.Col span={{ base: 12, md: 4 }}>
          <Card withBorder padding="md">
            <Stack gap={4}>
              <Text size="xs" c="dimmed">
                {t('issueDetail.occurrences')}
              </Text>
              <Text>{issue.occurrenceCount.toLocaleString(i18n.language || 'en')}</Text>
            </Stack>
          </Card>
        </Grid.Col>
      </Grid>

      <Card withBorder padding="md" data-testid="ai-analysis-card">
        <Stack gap="xs">
          <Group justify="space-between" align="baseline">
            <Title order={4}>{t('issueDetail.aiAnalysisTitle')}</Title>
            {issue.aiAnalysis && issue.aiAnalyzedAt && (
              <Text size="xs" c="dimmed">
                {issue.aiAnalysisModel ?? t('issueDetail.aiAnalysisUnknownModel')} ·{' '}
                {formatter.format(new Date(issue.aiAnalyzedAt))}
              </Text>
            )}
          </Group>
          {issue.aiAnalysis ? (
            // Render as preformatted text. The agent prompt produces markdown, but the web app
            // intentionally ships no markdown renderer (no `react-markdown` dep — see
            // apps/web/package.json). `whiteSpace: pre-wrap` keeps headings + bullet markers
            // readable; a future enhancement can render markdown properly when there's clear
            // UX demand.
            <Text style={{ whiteSpace: 'pre-wrap' }} size="sm">
              {issue.aiAnalysis}
            </Text>
          ) : (
            <Text c="dimmed" size="sm">
              {t('issueDetail.aiAnalysisEmpty')}
            </Text>
          )}
        </Stack>
      </Card>

      <Card withBorder padding="md">
        <Stack>
          <Group justify="space-between">
            <Title order={4}>{t('issueDetail.recentEvents')}</Title>
            {anySymbolicated && (
              <SegmentedControl
                size="xs"
                value={preferOriginal ? 'original' : 'raw'}
                onChange={(v) => setPreferOriginal(v === 'original')}
                data={[
                  { value: 'original', label: t('issueDetail.toggleOriginal') },
                  { value: 'raw', label: t('issueDetail.toggleRaw') },
                ]}
                aria-label={t('issueDetail.toggleAria')}
              />
            )}
          </Group>
          {eventsQ.isLoading && (
            <Center py="md">
              <Loader size="sm" />
            </Center>
          )}
          {eventsQ.isError && (
            <Alert color="red">
              <Text size="sm">{(eventsQ.error as Error).message}</Text>
            </Alert>
          )}
          {eventsQ.data && eventsQ.data.data.length === 0 && (
            <Text c="dimmed">{t('issueDetail.noEvents')}</Text>
          )}
          {eventsQ.data && eventsQ.data.data.length > 0 && (
            <Table data-testid="events-table">
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>{t('issueDetail.eventTime')}</Table.Th>
                  <Table.Th>{t('issueDetail.eventId')}</Table.Th>
                  <Table.Th>{t('issueDetail.payload')}</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {eventsQ.data.data.flatMap((event) => {
                  const frames = eventFrames[event.id] ?? [];
                  const breadcrumbs = eventBreadcrumbs[event.id] ?? [];
                  const meta = eventMetas[event.id] ?? {
                    tags: {},
                    contexts: {},
                    extra: {},
                  };
                  const web3 = eventWeb3[event.id];
                  const receivedAtMs = new Date(event.receivedAt).getTime();
                  return [
                    <Table.Tr key={event.id}>
                      <Table.Td>{formatter.format(new Date(event.receivedAt))}</Table.Td>
                      <Table.Td>
                        <Code>{event.id.slice(0, 8)}</Code>
                      </Table.Td>
                      <Table.Td>
                        <Code
                          style={{ maxWidth: 480, overflow: 'hidden', textOverflow: 'ellipsis' }}
                        >
                          {previewPayload(event.payload)}
                        </Code>
                      </Table.Td>
                    </Table.Tr>,
                    ...(hasAnyMeta(meta)
                      ? [
                          <Table.Tr key={`${event.id}-meta`}>
                            <Table.Td colSpan={3} style={{ paddingTop: 0 }}>
                              <EventDetailsView meta={meta} />
                            </Table.Td>
                          </Table.Tr>,
                        ]
                      : []),
                    ...(web3
                      ? [
                          <Table.Tr key={`${event.id}-web3`}>
                            <Table.Td colSpan={3} style={{ paddingTop: 0 }}>
                              <Web3Panel summary={web3} />
                            </Table.Td>
                          </Table.Tr>,
                        ]
                      : []),
                    ...(frames.length > 0
                      ? [
                          <Table.Tr key={`${event.id}-stack`}>
                            <Table.Td colSpan={3} style={{ paddingTop: 0 }}>
                              <StacktraceView frames={frames} preferOriginal={preferOriginal} />
                            </Table.Td>
                          </Table.Tr>,
                        ]
                      : []),
                    ...(breadcrumbs.length > 0
                      ? [
                          <Table.Tr key={`${event.id}-breadcrumbs`}>
                            <Table.Td colSpan={3} style={{ paddingTop: 0 }}>
                              <BreadcrumbsView
                                breadcrumbs={breadcrumbs}
                                referenceTime={receivedAtMs}
                              />
                            </Table.Td>
                          </Table.Tr>,
                        ]
                      : []),
                  ];
                })}
              </Table.Tbody>
            </Table>
          )}
          <Group justify="space-between">
            <Button variant="default" onClick={() => goToPage(undefined)} disabled={!cursor}>
              {t('pagination.first')}
            </Button>
            <Button
              variant="default"
              onClick={() => goToPage(eventsQ.data?.page.next)}
              disabled={!eventsQ.data?.page.next || eventsQ.isFetching}
            >
              {t('pagination.next')}
            </Button>
          </Group>
        </Stack>
      </Card>
    </Stack>
  );
}

function previewPayload(payload: unknown): string {
  try {
    const json = JSON.stringify(payload);
    return json.length > 200 ? `${json.slice(0, 200)}…` : json;
  } catch {
    return '<unserializable>';
  }
}

interface AssigneeChipProps {
  assigneeUserId: string | null;
  members: { userId: string; email: string; displayName: string | null }[];
  loading: boolean;
  onChange: (userId: string | null) => void;
}

/**
 * Chip-shaped trigger that opens a dropdown of org members. Clicking a member assigns the issue;
 * the "Unassign" item at the bottom clears it. The chip is wide enough to show either an initial
 * avatar (assigned) or a "+ Assign" hint (unassigned).
 */
function AssigneeChip({ assigneeUserId, members, loading, onChange }: AssigneeChipProps) {
  const { t } = useTranslation();
  const assignee = members.find((m) => m.userId === assigneeUserId);
  const display = assignee ? (assignee.displayName ?? assignee.email) : null;

  return (
    <Menu shadow="md" width={240} position="bottom-end" withinPortal>
      <Menu.Target>
        <Button
          size="xs"
          variant="light"
          color={assignee ? 'blue' : 'gray'}
          loading={loading}
          leftSection={
            assignee ? (
              <Avatar size={16} radius="xl" color="blue">
                <IconUser size={10} />
              </Avatar>
            ) : (
              <IconUserPlus size={14} />
            )
          }
          data-testid="issue-assignee-chip"
        >
          {assignee ? display : t('issues.assignee.unassigned')}
        </Button>
      </Menu.Target>
      <Menu.Dropdown>
        <Menu.Label>{t('issues.assignee.pickLabel')}</Menu.Label>
        {members.length === 0 ? (
          <Menu.Item disabled>{t('issues.assignee.noMembers')}</Menu.Item>
        ) : (
          members.map((m) => (
            <Menu.Item
              key={m.userId}
              onClick={() => onChange(m.userId)}
              leftSection={
                m.userId === assigneeUserId ? (
                  <IconCheck size={14} />
                ) : (
                  <span style={{ width: 14, display: 'inline-block' }} />
                )
              }
            >
              <Stack gap={0}>
                <Text size="sm">{m.displayName ?? m.email}</Text>
                {m.displayName && (
                  <Text size="xs" c="dimmed">
                    {m.email}
                  </Text>
                )}
              </Stack>
            </Menu.Item>
          ))
        )}
        {assigneeUserId && (
          <>
            <Menu.Divider />
            <Menu.Item color="red" onClick={() => onChange(null)}>
              {t('issues.assignee.unassign')}
            </Menu.Item>
          </>
        )}
      </Menu.Dropdown>
    </Menu>
  );
}
