import {
  Alert,
  Badge,
  Button,
  Card,
  Center,
  Group,
  Loader,
  Select,
  Stack,
  Table,
  Text,
  Title,
} from '@mantine/core';
import { IconRefresh } from '@tabler/icons-react';
import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams, useSearchParams } from 'react-router';

import type { IssueLevel, IssueStatus } from '../api/issues';
import { useIssues } from '../api/queries';
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
  const { orgSlug, projectId: rawProjectId } = useParams();
  const [search, setSearch] = useSearchParams();

  const projectId = Number(rawProjectId);
  const projectIdValid = Number.isFinite(projectId) && projectId > 0;

  useReportSoftError(
    !projectIdValid,
    `IssuesPage: invalid projectId param "${rawProjectId}"`,
  );
  const status = (search.get('status') as IssueStatus | null) ?? undefined;
  const level = (search.get('level') as IssueLevel | null) ?? undefined;
  const cursor = search.get('cursor') ?? undefined;

  const query = useIssues(
    {
      projectId,
      status: status && STATUS_VALUES.includes(status) ? status : undefined,
      level: level && LEVEL_VALUES.includes(level) ? level : undefined,
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

  const updateFilter = (name: 'status' | 'level', value: string | null) => {
    const next = new URLSearchParams(search);
    if (value) next.set(name, value);
    else next.delete(name);
    next.delete('cursor'); // changing a filter resets pagination
    setSearch(next, { replace: true });
  };

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
      <Group justify="space-between">
        <Title order={3}>{t('issues.title')}</Title>
        <Group gap="xs">
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
        </Group>
      </Group>

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
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {query.data.data.map((issue) => (
              <Table.Tr key={issue.id}>
                <Table.Td>
                  <Stack gap={2}>
                    <Link to={`/orgs/${orgSlug}/projects/${projectId}/issues/${issue.id}`}>
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
