import {
  Alert,
  Badge,
  Breadcrumbs,
  Button,
  Card,
  Center,
  Code,
  Grid,
  Group,
  Loader,
  SegmentedControl,
  Stack,
  Table,
  Text,
  Title,
} from '@mantine/core';
import { IconChevronRight } from '@tabler/icons-react';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams, useSearchParams } from 'react-router';

import type { IssueLevel, IssueStatus } from '../api/issues';
import { useIssue, useIssueEvents } from '../api/queries';
import { useReportSoftError } from '../lib/reportSoftError';

import {
  extractFrames,
  hasSymbolication,
  type RawFrame,
  StacktraceView,
} from './issue-detail/Stacktrace';

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
        <Group gap="sm">
          <Badge color={STATUS_COLOR[issue.status]} variant="light">
            {t(`issues.status.${issue.status}`)}
          </Badge>
          <Badge color={LEVEL_COLOR[issue.level]}>{t(`issues.level.${issue.level}`)}</Badge>
        </Group>
        <Title order={2}>{issue.title}</Title>
        {issue.culprit && (
          <Text c="dimmed" size="sm">
            {issue.culprit}
          </Text>
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
                    ...(frames.length > 0
                      ? [
                          <Table.Tr key={`${event.id}-stack`}>
                            <Table.Td colSpan={3} style={{ paddingTop: 0 }}>
                              <StacktraceView frames={frames} preferOriginal={preferOriginal} />
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
