import {
  ActionIcon,
  Alert,
  Badge,
  Box,
  Button,
  Card,
  Center,
  Code,
  CopyButton,
  Divider,
  Group,
  Loader,
  Menu,
  Modal,
  NumberFormatter,
  Select,
  SimpleGrid,
  Stack,
  Text,
  TextInput,
  ThemeIcon,
  Title,
  Tooltip,
} from '@mantine/core';
import { AreaChart } from '@mantine/charts';
import { useForm } from '@mantine/form';
import {
  IconArchive,
  IconArrowRight,
  IconDotsVertical,
  IconPencil,
  IconPlugConnected,
} from '@tabler/icons-react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, Navigate, useNavigate, useParams } from 'react-router';

import { ApiError } from '../api/client';
import { type Dsn } from '../api/keys';
import { archiveProject, createProject, renameProject, type Project } from '../api/projects';
import { queryKeys, useMyOrgs, usePlatforms, useProjects } from '../api/queries';
import { platformVisuals } from '../lib/platformVisuals';
import { formatRelativeTime } from '../lib/relativeTime';
import { useReportSoftError } from '../lib/reportSoftError';

interface DsnSuccess {
  project: Project;
  dsn: Dsn;
}

function describeApiError(err: unknown): string {
  return err instanceof ApiError ? (err.problem.detail ?? err.problem.title) : String(err);
}

export function ProjectsPage() {
  const { orgSlug } = useParams();
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const navigate = useNavigate();

  const orgsQuery = useMyOrgs();
  const org = orgsQuery.data?.find((o) => o.slug === orgSlug);
  const projectsQuery = useProjects(org?.id);
  const platformsQuery = usePlatforms();
  const platformOptions = platformsQuery.data?.map((p) => ({ value: p.slug, label: p.name })) ?? [];

  // Don't fire if user has no orgs (we redirect to /onboarding) or if first-org redirect is in
  // flight — only when user has orgs but the requested slug truly doesn't match any.
  const firstSlug = orgsQuery.data?.[0]?.slug;
  useReportSoftError(
    Boolean(
      orgsQuery.data &&
      orgsQuery.data.length > 0 &&
      !org &&
      orgSlug &&
      (!firstSlug || firstSlug === orgSlug),
    ),
    `ProjectsPage: org slug "${orgSlug}" not in user's memberships`,
  );

  const [createOpen, setCreateOpen] = useState(false);
  const [archiveTarget, setArchiveTarget] = useState<Project | null>(null);
  const [archiveError, setArchiveError] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [dsnSuccess, setDsnSuccess] = useState<DsnSuccess | null>(null);
  const [renameTarget, setRenameTarget] = useState<Project | null>(null);
  const [renameValue, setRenameValue] = useState('');
  const [renameError, setRenameError] = useState<string | null>(null);

  const form = useForm({
    initialValues: { name: '', platform: 'javascript' },
    validate: {
      name: (v) => (v.trim().length < 2 ? t('onboarding.errorProjectName') : null),
    },
  });

  const mutation = useMutation({
    mutationFn: async (values: { name: string; platform: string }): Promise<DsnSuccess> => {
      if (!org) throw new Error('org missing');
      // Server mints the first DSN inline (GH #26) — no chained POST that used to leave
      // orphan projects when the second call failed or the tab was closed mid-flow.
      return createProject(org.id, values);
    },
    onSuccess: async (result) => {
      if (org) {
        await queryClient.invalidateQueries({ queryKey: queryKeys.projects(org.id) });
      }
      setCreateOpen(false);
      form.reset();
      setError(null);
      setDsnSuccess(result);
    },
    onError: (err: unknown) => {
      setError(describeApiError(err));
    },
  });

  const archiveMutation = useMutation({
    mutationFn: (project: Project) => {
      if (!org) throw new Error('org missing');
      return archiveProject(org.id, project.id);
    },
    onSuccess: async () => {
      if (org) {
        await queryClient.invalidateQueries({ queryKey: queryKeys.projects(org.id) });
      }
      setArchiveTarget(null);
      setArchiveError(null);
    },
    onError: (err: unknown) => {
      setArchiveError(
        err instanceof ApiError ? (err.problem.detail ?? err.problem.title) : String(err),
      );
    },
  });

  const renameMutation = useMutation({
    mutationFn: () => {
      if (!org || !renameTarget) throw new Error('rename target missing');
      return renameProject(org.id, renameTarget.id, renameValue.trim());
    },
    onSuccess: async () => {
      if (org) {
        await queryClient.invalidateQueries({ queryKey: queryKeys.projects(org.id) });
      }
      setRenameTarget(null);
      setRenameValue('');
      setRenameError(null);
    },
    onError: (err: unknown) => {
      setRenameError(describeApiError(err));
    },
  });

  const trimmedRename = renameValue.trim();
  const canRename =
    Boolean(renameTarget) &&
    trimmedRename.length >= 2 &&
    trimmedRename !== (renameTarget?.name ?? '');

  const closeDsnModal = () => {
    setDsnSuccess(null);
  };

  const openProjectAndClose = (project: Project) => {
    if (!org) return;
    setDsnSuccess(null);
    navigate(`/orgs/${org.slug}/projects/${project.id}/issues`);
  };

  if (orgsQuery.isLoading) {
    return (
      <Center mih={200}>
        <Loader size="md" />
      </Center>
    );
  }

  if (orgsQuery.isError) {
    return (
      <Stack>
        <Title order={3}>{t('projects.title')}</Title>
        <Alert color="red" variant="light">
          {orgsQuery.error instanceof ApiError
            ? (orgsQuery.error.problem.detail ?? orgsQuery.error.problem.title)
            : String(orgsQuery.error)}
        </Alert>
      </Stack>
    );
  }

  if (orgsQuery.data && orgsQuery.data.length === 0) {
    return <Navigate to="/onboarding" replace />;
  }

  if (!org) {
    // Slug in the URL does not match any org the user belongs to. Steer them to a real one
    // (their first) instead of leaving them on a dead page with no recovery path.
    const firstSlug = orgsQuery.data?.[0]?.slug;
    if (firstSlug && firstSlug !== orgSlug) {
      return <Navigate to={`/orgs/${firstSlug}/projects`} replace />;
    }
    return (
      <Stack>
        <Title order={3}>{t('projects.title')}</Title>
        <Text c="dimmed">{t('projects.orgNotFound')}</Text>
      </Stack>
    );
  }

  return (
    <Stack>
      <Group justify="space-between">
        <Title order={3}>{t('projects.title')}</Title>
        <Button onClick={() => setCreateOpen(true)}>{t('projects.create')}</Button>
      </Group>

      {projectsQuery.isLoading ? (
        <Center mih={120}>
          <Loader size="sm" />
        </Center>
      ) : projectsQuery.data && projectsQuery.data.length === 0 ? (
        <Text c="dimmed">{t('projects.empty')}</Text>
      ) : (
        <SimpleGrid cols={{ base: 1, sm: 2, md: 3 }} spacing="md">
          {projectsQuery.data?.map((p) => (
            <ProjectCard
              key={p.id}
              project={p}
              orgSlug={org.slug}
              onArchive={(target) => {
                setArchiveError(null);
                setArchiveTarget(target);
              }}
              onRename={(target) => {
                setRenameError(null);
                setRenameValue(target.name);
                setRenameTarget(target);
              }}
            />
          ))}
        </SimpleGrid>
      )}

      <Modal
        opened={renameTarget !== null}
        onClose={() => {
          if (!renameMutation.isPending) {
            setRenameTarget(null);
            setRenameValue('');
            setRenameError(null);
          }
        }}
        title={t('projects.renameTitle', { name: renameTarget?.name ?? '' })}
        size="md"
      >
        <Stack>
          <TextInput
            label={t('projects.renameLabel')}
            value={renameValue}
            onChange={(e) => setRenameValue(e.currentTarget.value)}
            disabled={renameMutation.isPending}
            data-autofocus
            data-testid="project-rename-input"
          />
          {renameError ? (
            <Alert color="red" variant="light">
              {renameError}
            </Alert>
          ) : null}
          <Group justify="flex-end">
            <Button
              variant="default"
              onClick={() => setRenameTarget(null)}
              disabled={renameMutation.isPending}
            >
              {t('projects.renameCancel')}
            </Button>
            <Button
              loading={renameMutation.isPending}
              disabled={!canRename}
              onClick={() => renameMutation.mutate()}
              data-testid="project-rename-submit"
            >
              {t('projects.renameConfirm')}
            </Button>
          </Group>
        </Stack>
      </Modal>

      <Modal
        opened={archiveTarget !== null}
        onClose={() => {
          if (!archiveMutation.isPending) {
            setArchiveTarget(null);
            setArchiveError(null);
          }
        }}
        title={t('projects.archiveTitle')}
        size="md"
      >
        <Stack>
          <Text size="sm">{t('projects.archiveBody', { name: archiveTarget?.name ?? '' })}</Text>
          <Text size="xs" c="dimmed">
            {t('projects.archiveHint')}
          </Text>
          {archiveError ? (
            <Alert color="red" variant="light">
              {archiveError}
            </Alert>
          ) : null}
          <Group justify="flex-end">
            <Button
              variant="default"
              onClick={() => setArchiveTarget(null)}
              disabled={archiveMutation.isPending}
            >
              {t('projects.archiveCancel')}
            </Button>
            <Button
              color="red"
              loading={archiveMutation.isPending}
              onClick={() => archiveTarget && archiveMutation.mutate(archiveTarget)}
            >
              {t('projects.archiveConfirm')}
            </Button>
          </Group>
        </Stack>
      </Modal>

      <Modal
        opened={createOpen}
        onClose={() => setCreateOpen(false)}
        title={t('projects.createTitle')}
      >
        <form onSubmit={form.onSubmit((values) => mutation.mutate(values))}>
          <Stack>
            <TextInput
              label={t('onboarding.projectName')}
              {...form.getInputProps('name')}
              disabled={mutation.isPending}
            />
            <Select
              label={t('onboarding.platform')}
              data={platformOptions}
              {...form.getInputProps('platform')}
              disabled={mutation.isPending || platformsQuery.isLoading}
            />
            {error ? (
              <Alert color="red" variant="light">
                {error}
              </Alert>
            ) : null}
            <Button type="submit" loading={mutation.isPending}>
              {t('projects.create')}
            </Button>
          </Stack>
        </form>
      </Modal>

      <Modal
        opened={dsnSuccess !== null}
        onClose={closeDsnModal}
        title={t('projects.dsnTitle')}
        size="lg"
        closeOnClickOutside={false}
        closeOnEscape={false}
      >
        {dsnSuccess ? (
          <Stack>
            <Text size="sm">{t('projects.dsnHint')}</Text>
            <Code block>{dsnSuccess.dsn.dsn}</Code>
            <Group>
              <CopyButton value={dsnSuccess.dsn.dsn}>
                {({ copied, copy }) => (
                  <Button onClick={copy} variant="light">
                    {copied ? t('projects.copied') : t('projects.copyDsn')}
                  </Button>
                )}
              </CopyButton>
              <Button
                component={Link}
                to={`/orgs/${org.slug}/projects/${dsnSuccess.project.id}/connect`}
                onClick={() => setDsnSuccess(null)}
                variant="light"
                leftSection={<IconPlugConnected size={14} />}
                data-testid="dsn-modal-connect-cta"
              >
                {t('projects.connect')}
              </Button>
              <Button onClick={() => openProjectAndClose(dsnSuccess.project)}>
                {t('projects.continue')}
              </Button>
            </Group>
          </Stack>
        ) : null}
      </Modal>
    </Stack>
  );
}

interface ProjectCardProps {
  project: Project;
  orgSlug: string;
  onArchive: (project: Project) => void;
  onRename: (project: Project) => void;
}

function unresolvedColor(n: number): string {
  if (n === 0) return 'green';
  if (n < 10) return 'blue';
  if (n < 50) return 'yellow';
  return 'red';
}

function ProjectCard({ project, orgSlug, onArchive, onRename }: ProjectCardProps) {
  const { t, i18n } = useTranslation();
  const visuals = platformVisuals(project.platform);
  const PlatformIcon = visuals.Icon;
  const issuesUrl = `/orgs/${orgSlug}/projects/${project.id}/issues`;
  const createdRelative = formatRelativeTime(project.createdAt, i18n.language || 'en');
  const createdAbsolute = new Date(project.createdAt).toLocaleString(i18n.language || 'en');
  const stats = project.stats;
  const totalEventsInWindow = stats?.eventsByDay?.reduce((acc, b) => acc + b.count, 0) ?? 0;
  const hasSparklineData = stats != null && totalEventsInWindow > 0;
  const locale = i18n.language || 'en';
  const formatShortDay = (iso: string) =>
    new Date(iso).toLocaleDateString(locale, { month: 'short', day: 'numeric' });
  const formatTooltipDay = (iso: string) =>
    new Date(iso).toLocaleDateString(locale, {
      weekday: 'long',
      month: 'short',
      day: 'numeric',
    });
  const lastEventRelative =
    stats?.lastEventAt && i18n.language
      ? formatRelativeTime(stats.lastEventAt, i18n.language || 'en')
      : null;
  const lastEventAbsolute = stats?.lastEventAt
    ? new Date(stats.lastEventAt).toLocaleString(i18n.language || 'en')
    : null;

  return (
    <Card
      component={Link}
      to={issuesUrl}
      shadow="xs"
      padding="lg"
      radius="md"
      withBorder
      data-testid={`project-card-${project.slug}`}
      aria-label={t('projects.openAria', { name: project.name })}
      style={{
        textDecoration: 'none',
        color: 'inherit',
        cursor: 'pointer',
        transition: 'transform 120ms ease, box-shadow 120ms ease, border-color 120ms ease',
      }}
      onMouseEnter={(e) => {
        e.currentTarget.style.transform = 'translateY(-2px)';
        e.currentTarget.style.boxShadow = 'var(--mantine-shadow-md)';
        e.currentTarget.style.borderColor = `var(--mantine-color-${visuals.color}-5)`;
      }}
      onMouseLeave={(e) => {
        e.currentTarget.style.transform = '';
        e.currentTarget.style.boxShadow = '';
        e.currentTarget.style.borderColor = '';
      }}
    >
      <Group justify="space-between" wrap="nowrap" align="flex-start" mb="sm">
        <Group gap="sm" wrap="nowrap" align="flex-start" style={{ minWidth: 0, flex: 1 }}>
          <ThemeIcon variant="light" color={visuals.color} size={40} radius="md">
            <PlatformIcon size={22} />
          </ThemeIcon>
          <Stack gap={2} style={{ minWidth: 0, flex: 1 }}>
            <Text size="md" fw={600} lineClamp={1}>
              {project.name}
            </Text>
            <Code style={{ fontSize: 11, padding: '0 4px', alignSelf: 'flex-start' }}>
              {project.slug}
            </Code>
          </Stack>
        </Group>
        <Box
          onClick={(e) => {
            e.preventDefault();
            e.stopPropagation();
          }}
        >
          <Menu shadow="md" width={180} position="bottom-end" withinPortal>
            <Menu.Target>
              <ActionIcon
                variant="subtle"
                color="gray"
                size="sm"
                aria-label={t('projects.menuAria', { name: project.name })}
              >
                <IconDotsVertical size={16} />
              </ActionIcon>
            </Menu.Target>
            <Menu.Dropdown>
              <Menu.Item component={Link} to={issuesUrl} leftSection={<IconArrowRight size={14} />}>
                {t('projects.viewIssues')}
              </Menu.Item>
              <Menu.Item
                component={Link}
                to={`/orgs/${orgSlug}/projects/${project.id}/connect`}
                leftSection={<IconPlugConnected size={14} />}
                data-testid={`project-connect-${project.slug}`}
              >
                {t('projects.connect')}
              </Menu.Item>
              <Menu.Item
                leftSection={<IconPencil size={14} />}
                onClick={() => onRename(project)}
                aria-label={t('projects.renameAria', { name: project.name })}
                data-testid={`project-rename-${project.slug}`}
              >
                {t('projects.renameAction')}
              </Menu.Item>
              <Menu.Divider />
              <Menu.Item
                color="red"
                leftSection={<IconArchive size={14} />}
                onClick={() => onArchive(project)}
                aria-label={t('projects.archiveAria', { name: project.name })}
              >
                {t('projects.archiveAction')}
              </Menu.Item>
            </Menu.Dropdown>
          </Menu>
        </Box>
      </Group>

      {stats ? (
        <>
          <SimpleGrid cols={3} spacing="xs" mb="sm" data-testid={`project-stats-${project.slug}`}>
            <Stack gap={2}>
              <Text size="xs" c="dimmed" tt="uppercase" fw={600}>
                {t('projects.statUnresolved')}
              </Text>
              <Title order={4} c={unresolvedColor(stats.unresolvedIssueCount)}>
                <NumberFormatter value={stats.unresolvedIssueCount} thousandSeparator />
              </Title>
            </Stack>
            <Stack gap={2}>
              <Text size="xs" c="dimmed" tt="uppercase" fw={600}>
                {t('projects.statEvents24h')}
              </Text>
              <Title order={4}>
                <NumberFormatter value={stats.events24h} thousandSeparator />
              </Title>
            </Stack>
            <Stack gap={2}>
              <Text size="xs" c="dimmed" tt="uppercase" fw={600}>
                {t('projects.statEvents7d')}
              </Text>
              <Title order={4}>
                <NumberFormatter value={stats.events7d} thousandSeparator />
              </Title>
            </Stack>
          </SimpleGrid>

          {hasSparklineData ? (
            <Box mb="sm" data-testid={`project-sparkline-${project.slug}`}>
              <Text size="xs" c="dimmed" mb={4}>
                {t('projects.sparklineCaption')}
              </Text>
              <AreaChart
                h={90}
                w="100%"
                data={stats.eventsByDay}
                dataKey="day"
                series={[{ name: 'count', color: 'green.6', label: t('projects.events') }]}
                curveType="monotone"
                withDots={false}
                withYAxis={false}
                gridAxis="none"
                strokeWidth={1.5}
                fillOpacity={0.25}
                xAxisProps={{
                  tickFormatter: formatShortDay,
                  interval: 'preserveStartEnd',
                  minTickGap: 24,
                }}
                tooltipProps={{ labelFormatter: formatTooltipDay }}
              />
            </Box>
          ) : (
            <Box mb="sm">
              <Text size="xs" c="dimmed" fs="italic" data-testid={`project-no-events-${project.slug}`}>
                {t('projects.noEvents')}
              </Text>
            </Box>
          )}

          <Divider mb="sm" />
        </>
      ) : null}

      <Group justify="space-between" wrap="nowrap" gap="xs">
        <Tooltip label={t('projects.createdAt', { relative: createdRelative })} withArrow>
          <Badge
            size="sm"
            variant="light"
            color={visuals.color}
            leftSection={<PlatformIcon size={12} />}
          >
            {project.platform}
          </Badge>
        </Tooltip>
        {stats?.lastEventAt && lastEventAbsolute ? (
          <Tooltip label={lastEventAbsolute} withArrow>
            <Text size="xs" c="dimmed">
              {t('projects.lastEventAgo', { relative: lastEventRelative })}
            </Text>
          </Tooltip>
        ) : stats ? (
          <Text size="xs" c="dimmed">
            {t('projects.lastEventNever')}
          </Text>
        ) : (
          <Tooltip label={createdAbsolute} withArrow>
            <Text size="xs" c="dimmed">
              {t('projects.createdAt', { relative: createdRelative })}
            </Text>
          </Tooltip>
        )}
      </Group>
    </Card>
  );
}
