import {
  ActionIcon,
  Alert,
  Button,
  Card,
  Center,
  Code,
  CopyButton,
  Group,
  Loader,
  Modal,
  Select,
  SimpleGrid,
  Stack,
  Text,
  TextInput,
  Title,
} from '@mantine/core';
import { useForm } from '@mantine/form';
import { IconTrash } from '@tabler/icons-react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, Navigate, useNavigate, useParams } from 'react-router';

import { ApiError } from '../api/client';
import { createDsn, type Dsn } from '../api/keys';
import { archiveProject, createProject, type Project } from '../api/projects';
import { queryKeys, useMyOrgs, usePlatforms, useProjects } from '../api/queries';

interface DsnSuccess {
  project: Project;
  dsn: Dsn | null;
  dsnError: string | null;
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

  const [createOpen, setCreateOpen] = useState(false);
  const [archiveTarget, setArchiveTarget] = useState<Project | null>(null);
  const [archiveError, setArchiveError] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [dsnSuccess, setDsnSuccess] = useState<DsnSuccess | null>(null);

  const form = useForm({
    initialValues: { name: '', platform: 'javascript' },
    validate: {
      name: (v) => (v.trim().length < 2 ? t('onboarding.errorProjectName') : null),
    },
  });

  const mutation = useMutation({
    mutationFn: async (values: { name: string; platform: string }): Promise<DsnSuccess> => {
      if (!org) throw new Error('org missing');
      const project = await createProject(org.id, values);
      try {
        const dsn = await createDsn(project.id);
        return { project, dsn, dsnError: null };
      } catch (err) {
        // Project row exists but key issuance failed — surface the error in the success modal so
        // the user can retry without losing the (already created) project.
        return { project, dsn: null, dsnError: describeApiError(err) };
      }
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

  const dsnRetryMutation = useMutation({
    mutationFn: async (project: Project) => {
      const dsn = await createDsn(project.id);
      return { project, dsn };
    },
    onSuccess: ({ project, dsn }) => {
      setDsnSuccess({ project, dsn, dsnError: null });
    },
    onError: (err: unknown) => {
      setDsnSuccess((prev) => (prev ? { ...prev, dsnError: describeApiError(err) } : prev));
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

  const closeDsnModal = () => {
    if (dsnRetryMutation.isPending) return;
    setDsnSuccess(null);
    dsnRetryMutation.reset();
  };

  const openProjectAndClose = (project: Project) => {
    if (!org) return;
    setDsnSuccess(null);
    dsnRetryMutation.reset();
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
        <SimpleGrid cols={{ base: 1, sm: 2, md: 3 }}>
          {projectsQuery.data?.map((p) => (
            <Card key={p.id} shadow="xs" padding="lg" radius="md" withBorder pos="relative">
              <Card.Section
                component={Link}
                to={`/orgs/${org.slug}/projects/${p.id}/issues`}
                inheritPadding
                py="lg"
                style={{ textDecoration: 'none', color: 'inherit' }}
              >
                <Group justify="space-between" wrap="nowrap">
                  <Title order={5}>{p.name}</Title>
                  <Text size="xs" c="dimmed">
                    {p.platform}
                  </Text>
                </Group>
                <Text size="sm" c="dimmed">
                  {p.slug}
                </Text>
              </Card.Section>
              <ActionIcon
                variant="subtle"
                color="red"
                size="sm"
                pos="absolute"
                top={8}
                right={8}
                aria-label={t('projects.archiveAria', { name: p.name })}
                onClick={(e) => {
                  e.preventDefault();
                  e.stopPropagation();
                  setArchiveError(null);
                  setArchiveTarget(p);
                }}
              >
                <IconTrash size={14} />
              </ActionIcon>
            </Card>
          ))}
        </SimpleGrid>
      )}

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
            {dsnSuccess.dsn ? (
              <>
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
                  <Button onClick={() => openProjectAndClose(dsnSuccess.project)}>
                    {t('projects.continue')}
                  </Button>
                </Group>
              </>
            ) : (
              <>
                <Text size="sm">{t('projects.dsnRetryHint')}</Text>
                {dsnSuccess.dsnError ? (
                  <Alert color="red" variant="light">
                    {dsnSuccess.dsnError}
                  </Alert>
                ) : null}
                <Group>
                  <Button
                    loading={dsnRetryMutation.isPending}
                    onClick={() => dsnRetryMutation.mutate(dsnSuccess.project)}
                  >
                    {t('projects.dsnRetry')}
                  </Button>
                  <Button
                    variant="default"
                    disabled={dsnRetryMutation.isPending}
                    onClick={() => openProjectAndClose(dsnSuccess.project)}
                  >
                    {t('projects.continue')}
                  </Button>
                </Group>
              </>
            )}
          </Stack>
        ) : null}
      </Modal>
    </Stack>
  );
}
