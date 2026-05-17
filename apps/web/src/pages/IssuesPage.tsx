import {
  ActionIcon,
  Alert,
  Badge,
  Button,
  Card,
  Center,
  CloseButton,
  Group,
  Loader,
  Menu,
  Select,
  Stack,
  Table,
  Text,
  TextInput,
  Title,
} from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import {
  IconCheck,
  IconDotsVertical,
  IconEyeOff,
  IconRefresh,
  IconRotate,
  IconSearch,
} from '@tabler/icons-react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams, useSearchParams } from 'react-router';

import { ApiError } from '../api/client';
import {
  updateIssueStatus,
  type AssigneeFilter,
  type IssueLevel,
  type IssueStatus,
} from '../api/issues';
import { useIssues, useMyOrgs, useOrgMembers } from '../api/queries';
import { useReportSoftError } from '../lib/reportSoftError';

const STATUS_VALUES: readonly IssueStatus[] = ['unresolved', 'resolved', 'ignored'];
const LEVEL_VALUES: readonly IssueLevel[] = ['fatal', 'error', 'warning', 'info', 'debug'];

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

export function IssuesPage() {
  const { t, i18n } = useTranslation();
  const queryClient = useQueryClient();
  const { orgSlug, projectId: rawProjectId } = useParams();
  const [search, setSearch] = useSearchParams();
  const [triageError, setTriageError] = useState<string | null>(null);

  const projectId = Number(rawProjectId);
  const projectIdValid = Number.isFinite(projectId) && projectId > 0;

  useReportSoftError(!projectIdValid, `IssuesPage: invalid projectId param "${rawProjectId}"`);
  const status = (search.get('status') as IssueStatus | null) ?? undefined;
  const level = (search.get('level') as IssueLevel | null) ?? undefined;
  const assignee = (search.get('assignee') as AssigneeFilter | null) ?? undefined;
  const urlSearchText = search.get('q') ?? '';
  const cursor = search.get('cursor') ?? undefined;

  // Search is debounced locally so each keystroke doesn't fire a fetch. The URL stays the
  // canonical source — once the debounce settles, we write the new value to the query string
  // and the corresponding useQuery refetches because its key changes.
  const [searchDraft, setSearchDraft] = useState(urlSearchText);
  const [debouncedSearch] = useDebouncedValue(searchDraft, 300);

  useEffect(() => {
    if (debouncedSearch === urlSearchText) return;
    const next = new URLSearchParams(search);
    if (debouncedSearch.trim()) next.set('q', debouncedSearch.trim());
    else next.delete('q');
    next.delete('cursor');
    setSearch(next, { replace: true });
    // eslint-disable-next-line react-hooks/exhaustive-deps -- intentional: only react to debouncedSearch
  }, [debouncedSearch]);

  // Keep the input synced with browser back/forward navigation.
  useEffect(() => {
    if (urlSearchText !== searchDraft) setSearchDraft(urlSearchText);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- intentional: only sync from URL
  }, [urlSearchText]);

  const orgsQ = useMyOrgs({ enabled: projectIdValid });
  const currentOrg = (Array.isArray(orgsQ.data) ? orgsQ.data : []).find((o) => o.slug === orgSlug);
  const membersQ = useOrgMembers(currentOrg?.id, { enabled: projectIdValid && !!currentOrg });
  const members = Array.isArray(membersQ.data) ? membersQ.data : [];

  const query = useIssues(
    {
      projectId,
      status: status && STATUS_VALUES.includes(status) ? status : undefined,
      level: level && LEVEL_VALUES.includes(level) ? level : undefined,
      q: urlSearchText || undefined,
      assignee,
      cursor,
      limit: 50,
    },
    { enabled: projectIdValid },
  );

  const formatter = useMemo(
    () =>
      new Intl.DateTimeFormat(i18n.language || 'en', {
        dateStyle: 'medium',
        timeStyle: 'short',
      }),
    [i18n.language],
  );

  const updateFilter = (name: 'status' | 'level' | 'assignee', value: string | null) => {
    const next = new URLSearchParams(search);
    if (value) next.set(name, value);
    else next.delete(name);
    next.delete('cursor'); // changing a filter resets pagination
    setSearch(next, { replace: true });
  };

  // Assignee options shown in the Select. "All" maps to no constraint (cleared param),
  // "Me" and "Unassigned" are the well-known shortcuts the backend understands by name,
  // and each org member becomes a row keyed by their UUID.
  const assigneeOptions = [
    { value: 'all', label: t('issues.assigneeFilter.all') },
    { value: 'me', label: t('issues.assigneeFilter.me') },
    { value: 'none', label: t('issues.assigneeFilter.unassigned') },
    ...members.map((m) => ({
      value: m.userId,
      label: m.displayName ?? m.email,
    })),
  ];

  const triageMutation = useMutation({
    mutationFn: ({ issueId, status }: { issueId: number; status: IssueStatus }) =>
      updateIssueStatus(projectId, issueId, status),
    onSuccess: () => {
      setTriageError(null);
      // Invalidate every issues page query for this project — the issue may have moved
      // between status buckets, so even the current view's totals are stale.
      void queryClient.invalidateQueries({ queryKey: ['issues'] });
    },
    onError: (err) => {
      setTriageError(
        err instanceof ApiError ? (err.problem.detail ?? err.problem.title) : String(err),
      );
    },
  });

  const goToPage = (cursorValue: string | undefined) => {
    const next = new URLSearchParams(search);
    if (cursorValue) next.set('cursor', cursorValue);
    else next.delete('cursor');
    setSearch(next);
  };

  if (!projectIdValid) {
    return (
      <Stack>
        <Title order={3}>{t('issues.title')}</Title>
        <Alert color="yellow">{t('issues.invalidProjectId')}</Alert>
      </Stack>
    );
  }

  return (
    <Stack>
      <Group justify="space-between" align="flex-start" wrap="wrap">
        <Title order={3}>{t('issues.title')}</Title>
        <Group gap="xs" wrap="wrap">
          <TextInput
            placeholder={t('issues.searchPlaceholder')}
            value={searchDraft}
            onChange={(e) => setSearchDraft(e.currentTarget.value)}
            leftSection={<IconSearch size={14} />}
            rightSection={
              searchDraft ? (
                <CloseButton
                  aria-label={t('issues.searchClear')}
                  onClick={() => setSearchDraft('')}
                  size="sm"
                />
              ) : null
            }
            w={260}
            data-testid="issues-search-input"
          />
          <Select
            placeholder={t('issues.statusFilter')}
            data={STATUS_VALUES.map((v) => ({ value: v, label: t(`issues.status.${v}`) }))}
            value={status ?? null}
            onChange={(v) => updateFilter('status', v)}
            clearable
            w={170}
          />
          <Select
            placeholder={t('issues.levelFilter')}
            data={LEVEL_VALUES.map((v) => ({ value: v, label: t(`issues.level.${v}`) }))}
            value={level ?? null}
            onChange={(v) => updateFilter('level', v)}
            clearable
            w={170}
          />
          <Select
            placeholder={t('issues.assigneeFilter.placeholder')}
            data={assigneeOptions}
            value={assignee ?? null}
            onChange={(v) => updateFilter('assignee', v && v !== 'all' ? v : null)}
            clearable
            w={200}
            data-testid="issues-assignee-filter"
          />
        </Group>
      </Group>

      {triageError && (
        <Alert color="red" variant="light" withCloseButton onClose={() => setTriageError(null)}>
          {triageError}
        </Alert>
      )}

      {query.isError && (
        <Alert
          color="red"
          title={t('errors.generic')}
          icon={<IconRefresh size={16} />}
          withCloseButton={false}
        >
          <Group justify="space-between">
            <Text size="sm">{(query.error as Error).message}</Text>
            <Button size="xs" variant="light" onClick={() => void query.refetch()}>
              {t('errors.tryAgain')}
            </Button>
          </Group>
        </Alert>
      )}

      {query.isLoading && (
        <Center py="xl">
          <Loader />
        </Center>
      )}

      {query.data && query.data.data.length === 0 && (
        <Card withBorder padding="xl">
          <Text c="dimmed">{t('issues.empty')}</Text>
        </Card>
      )}

      {query.data && query.data.data.length > 0 && (
        <Table highlightOnHover striped data-testid="issues-table">
          <Table.Thead>
            <Table.Tr>
              <Table.Th>{t('issues.title')}</Table.Th>
              <Table.Th>{t('issues.statusFilter')}</Table.Th>
              <Table.Th>{t('issues.levelFilter')}</Table.Th>
              <Table.Th>{t('issues.lastSeen')}</Table.Th>
              <Table.Th>{t('issues.occurrences')}</Table.Th>
              <Table.Th aria-label={t('issues.actionsColumn')} />
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {query.data.data.map((issue) => (
              <Table.Tr key={issue.id} data-testid="issues-row">
                <Table.Td>
                  <Stack gap={2}>
                    <Link
                      to={`/orgs/${orgSlug}/projects/${projectId}/issues/${issue.id}`}
                      data-testid={`issue-link-${issue.id}`}
                    >
                      <Text fw={500}>{issue.title}</Text>
                    </Link>
                    {issue.culprit && (
                      <Text size="xs" c="dimmed">
                        {issue.culprit}
                      </Text>
                    )}
                  </Stack>
                </Table.Td>
                <Table.Td>
                  <Badge color={STATUS_COLOR[issue.status]} variant="light">
                    {t(`issues.status.${issue.status}`)}
                  </Badge>
                </Table.Td>
                <Table.Td>
                  <Badge color={LEVEL_COLOR[issue.level]}>{t(`issues.level.${issue.level}`)}</Badge>
                </Table.Td>
                <Table.Td>{formatter.format(new Date(issue.lastSeenAt))}</Table.Td>
                <Table.Td>{issue.occurrenceCount.toLocaleString(i18n.language || 'en')}</Table.Td>
                <Table.Td style={{ width: 40 }}>
                  <Menu shadow="md" width={180} position="bottom-end" withinPortal>
                    <Menu.Target>
                      <ActionIcon
                        variant="subtle"
                        color="gray"
                        size="sm"
                        aria-label={t('issues.actionsAria', { title: issue.title })}
                        data-testid={`issue-actions-${issue.id}`}
                      >
                        <IconDotsVertical size={16} />
                      </ActionIcon>
                    </Menu.Target>
                    <Menu.Dropdown>
                      {issue.status !== 'resolved' && (
                        <Menu.Item
                          leftSection={<IconCheck size={14} />}
                          onClick={() =>
                            triageMutation.mutate({ issueId: issue.id, status: 'resolved' })
                          }
                          data-testid={`issue-resolve-${issue.id}`}
                        >
                          {t('issues.actions.resolve')}
                        </Menu.Item>
                      )}
                      {issue.status !== 'ignored' && (
                        <Menu.Item
                          leftSection={<IconEyeOff size={14} />}
                          onClick={() =>
                            triageMutation.mutate({ issueId: issue.id, status: 'ignored' })
                          }
                          data-testid={`issue-ignore-${issue.id}`}
                        >
                          {t('issues.actions.ignore')}
                        </Menu.Item>
                      )}
                      {issue.status !== 'unresolved' && (
                        <Menu.Item
                          leftSection={<IconRotate size={14} />}
                          onClick={() =>
                            triageMutation.mutate({ issueId: issue.id, status: 'unresolved' })
                          }
                          data-testid={`issue-reopen-${issue.id}`}
                        >
                          {t('issues.actions.reopen')}
                        </Menu.Item>
                      )}
                    </Menu.Dropdown>
                  </Menu>
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}

      <Group justify="space-between">
        <Button variant="default" onClick={() => goToPage(undefined)} disabled={!cursor}>
          {t('pagination.first')}
        </Button>
        <Button
          variant="default"
          onClick={() => goToPage(query.data?.page.next)}
          disabled={!query.data?.page.next || query.isFetching}
        >
          {t('pagination.next')}
        </Button>
      </Group>
    </Stack>
  );
}
