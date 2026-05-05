import {
  Alert,
  Button,
  Card,
  Center,
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
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, Navigate, useParams } from 'react-router';

import { ApiError } from '../api/client';
import { createProject } from '../api/projects';
import { queryKeys, useMyOrgs, useProjects } from '../api/queries';

export function ProjectsPage() {
  const { orgSlug } = useParams();
  const { t } = useTranslation();
  const queryClient = useQueryClient();

  const orgsQuery = useMyOrgs();
  const org = orgsQuery.data?.find((o) => o.slug === orgSlug);
  const projectsQuery = useProjects(org?.id);

  const [createOpen, setCreateOpen] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const form = useForm({
    initialValues: { name: '', platform: 'javascript' },
    validate: {
      name: (v) => (v.trim().length < 2 ? t('onboarding.errorProjectName') : null),
    },
  });

  const mutation = useMutation({
    mutationFn: (values: { name: string; platform: string }) => {
      if (!org) throw new Error('org missing');
      return createProject(org.id, values);
    },
    onSuccess: async () => {
      if (org) {
        await queryClient.invalidateQueries({ queryKey: queryKeys.projects(org.id) });
      }
      setCreateOpen(false);
      form.reset();
      setError(null);
    },
    onError: (err: unknown) => {
      setError(err instanceof ApiError ? err.problem.detail ?? err.problem.title : String(err));
    },
  });

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
            <Card
              key={p.id}
              component={Link}
              to={`/orgs/${org.slug}/projects/${p.id}/issues`}
              shadow="xs"
              padding="lg"
              radius="md"
              withBorder
            >
              <Group justify="space-between">
                <Title order={5}>{p.name}</Title>
                <Text size="xs" c="dimmed">
                  {p.platform}
                </Text>
              </Group>
              <Text size="sm" c="dimmed">
                {p.slug}
              </Text>
            </Card>
          ))}
        </SimpleGrid>
      )}

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
              data={[
                { value: 'javascript', label: 'JavaScript / Browser' },
                { value: 'react', label: 'React' },
                { value: 'java-spring', label: 'Java / Spring Boot' },
              ]}
              {...form.getInputProps('platform')}
              disabled={mutation.isPending}
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
    </Stack>
  );
}
