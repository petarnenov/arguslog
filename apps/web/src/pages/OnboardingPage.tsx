import {
  Alert,
  Button,
  Card,
  Center,
  Code,
  CopyButton,
  Group,
  Modal,
  Select,
  Stack,
  Text,
  TextInput,
  Title,
} from '@mantine/core';
import { useForm } from '@mantine/form';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router';

import { ApiError } from '../api/client';
import { type Dsn } from '../api/keys';
import { createOrg, type Org } from '../api/orgs';
import { createProject, type Project } from '../api/projects';
import { queryKeys, usePlatforms } from '../api/queries';

interface SuccessState {
  org: Org;
  project: Project;
  dsn: Dsn;
}

export function OnboardingPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const [success, setSuccess] = useState<SuccessState | null>(null);
  const [error, setError] = useState<string | null>(null);

  const platformsQuery = usePlatforms();
  const platformOptions = platformsQuery.data?.map((p) => ({ value: p.slug, label: p.name })) ?? [];

  const form = useForm({
    initialValues: { orgName: '', projectName: '', platform: 'javascript' },
    validate: {
      orgName: (v) => (v.trim().length < 2 ? t('onboarding.errorOrgName') : null),
      projectName: (v) => (v.trim().length < 2 ? t('onboarding.errorProjectName') : null),
    },
  });

  const mutation = useMutation({
    mutationFn: async (values: { orgName: string; projectName: string; platform: string }) => {
      const org = await createOrg(values.orgName);
      // Project + first DSN are minted in one atomic call (GH #26).
      const { project, dsn } = await createProject(org.id, {
        name: values.projectName,
        platform: values.platform,
      });
      return { org, project, dsn };
    },
    onSuccess: async (result) => {
      setError(null);
      setSuccess(result);
      await queryClient.invalidateQueries({ queryKey: queryKeys.myOrgs() });
      await queryClient.invalidateQueries({ queryKey: queryKeys.projects(result.org.id) });
    },
    onError: (err: unknown) => {
      // Mirror to console so a misconfigured Alert never silently swallows the failure reason.
      console.error('[onboarding] create flow failed', err);
      setError(err instanceof ApiError ? (err.problem.detail ?? err.problem.title) : String(err));
    },
  });

  const handleClose = () => {
    if (!success) return;
    navigate(`/orgs/${success.org.slug}/projects/${success.project.id}/issues`, { replace: true });
  };

  return (
    <Center mih="100vh" p="md">
      <Card shadow="sm" padding="xl" radius="md" withBorder w={520}>
        <form onSubmit={form.onSubmit((values) => mutation.mutate(values))}>
          <Stack>
            <Title order={3}>{t('onboarding.title')}</Title>
            <TextInput
              label={t('onboarding.orgName')}
              {...form.getInputProps('orgName')}
              disabled={mutation.isPending}
            />
            <TextInput
              label={t('onboarding.projectName')}
              {...form.getInputProps('projectName')}
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
            <Button type="submit" fullWidth loading={mutation.isPending}>
              {t('onboarding.create')}
            </Button>
          </Stack>
        </form>
      </Card>

      <Modal
        opened={Boolean(success)}
        onClose={handleClose}
        title={t('onboarding.successTitle')}
        size="lg"
        closeOnClickOutside={false}
        closeOnEscape={false}
      >
        {success ? (
          <Stack>
            <Text size="sm">{t('onboarding.successDsnHint')}</Text>
            <Code block>{success.dsn.dsn}</Code>
            <Group>
              <CopyButton value={success.dsn.dsn}>
                {({ copied, copy }) => (
                  <Button onClick={copy} variant="light">
                    {copied ? t('onboarding.copied') : t('onboarding.copy')}
                  </Button>
                )}
              </CopyButton>
              <Button onClick={handleClose}>{t('onboarding.continue')}</Button>
            </Group>
          </Stack>
        ) : null}
      </Modal>
    </Center>
  );
}
