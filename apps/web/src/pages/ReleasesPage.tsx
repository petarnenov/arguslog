import {
  ActionIcon,
  Alert,
  Button,
  Card,
  Center,
  Code,
  Group,
  Loader,
  Modal,
  Stack,
  Table,
  Text,
  TextInput,
  Title,
} from '@mantine/core';
import { useForm } from '@mantine/form';
import { IconPencil, IconPlus, IconTrash } from '@tabler/icons-react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, Navigate, useParams } from 'react-router';

import { ApiError } from '../api/client';
import { queryKeys, useMyOrgs, useReleases } from '../api/queries';
import { createRelease, deleteRelease, type Release, updateRelease } from '../api/releases';
import { useReportSoftError } from '../lib/reportSoftError';

interface DraftValues {
  version: string;
}

export function ReleasesPage() {
  const { orgSlug, projectId: rawProjectId } = useParams();
  const projectId = rawProjectId ? Number(rawProjectId) : undefined;
  const { t } = useTranslation();
  const queryClient = useQueryClient();

  const orgsQuery = useMyOrgs();
  const org = orgsQuery.data?.find((o) => o.slug === orgSlug);
  const releasesQuery = useReleases(projectId);

  useReportSoftError(
    Boolean(orgsQuery.data && !org && orgSlug),
    `ReleasesPage: org slug "${orgSlug}" not in user's memberships`,
  );

  const [editing, setEditing] = useState<Release | 'new' | null>(null);
  const [confirmDelete, setConfirmDelete] = useState<Release | null>(null);
  const [error, setError] = useState<string | null>(null);

  const isEditMode = editing && editing !== 'new';

  const form = useForm<DraftValues>({
    initialValues: { version: '' },
    validate: {
      version: (v) => (v.trim().length < 1 ? t('releases.errorVersion') : null),
    },
  });

  function openCreate() {
    setError(null);
    form.setValues({ version: '' });
    setEditing('new');
  }

  function openEdit(r: Release) {
    setError(null);
    form.setValues({ version: r.version });
    setEditing(r);
  }

  function close() {
    setEditing(null);
    form.reset();
    setError(null);
  }

  const saveMutation = useMutation({
    mutationFn: async (values: DraftValues) => {
      if (projectId == null) throw new Error('projectId missing');
      if (isEditMode) {
        return updateRelease(projectId, editing.id, values.version.trim());
      }
      return createRelease(projectId, values.version.trim());
    },
    onSuccess: async () => {
      if (projectId != null) {
        await queryClient.invalidateQueries({ queryKey: queryKeys.releases(projectId) });
      }
      close();
    },
    onError: (err: unknown) => {
      setError(err instanceof ApiError ? (err.problem.detail ?? err.problem.title) : String(err));
    },
  });

  const deleteMutation = useMutation({
    mutationFn: async (id: number) => {
      if (projectId == null) throw new Error('projectId missing');
      return deleteRelease(projectId, id);
    },
    onSuccess: async () => {
      if (projectId != null) {
        await queryClient.invalidateQueries({ queryKey: queryKeys.releases(projectId) });
      }
      setConfirmDelete(null);
    },
  });

  if (projectId == null || Number.isNaN(projectId)) {
    return <Navigate to={`/orgs/${orgSlug}/projects`} replace />;
  }

  if (orgsQuery.isLoading) {
    return (
      <Center mih={200}>
        <Loader />
      </Center>
    );
  }

  if (!org) {
    return (
      <Stack>
        <Title order={3}>{t('releases.title')}</Title>
        <Text c="dimmed">{t('projects.orgNotFound')}</Text>
      </Stack>
    );
  }

  return (
    <Stack>
      <Group justify="space-between">
        <Title order={3}>{t('releases.title')}</Title>
        <Button leftSection={<IconPlus size={16} />} onClick={openCreate}>
          {t('releases.new')}
        </Button>
      </Group>

      <Text c="dimmed" size="sm">
        {t('releases.intro')}
      </Text>

      {releasesQuery.isLoading ? (
        <Center mih={120}>
          <Loader size="sm" />
        </Center>
      ) : releasesQuery.data && releasesQuery.data.length === 0 ? (
        <Card withBorder padding="lg">
          <Text c="dimmed">{t('releases.empty')}</Text>
        </Card>
      ) : (
        <Card withBorder padding={0}>
          <Table data-testid="releases-table">
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('releases.colVersion')}</Table.Th>
                <Table.Th style={{ width: 200 }}>{t('releases.colCreated')}</Table.Th>
                <Table.Th style={{ width: 110, textAlign: 'right' }}>
                  {t('releases.colActions')}
                </Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {releasesQuery.data?.map((r) => (
                <Table.Tr key={r.id} data-testid={`release-row-${r.id}`}>
                  <Table.Td>
                    <Link
                      to={`/orgs/${orgSlug}/projects/${projectId}/releases/${r.id}`}
                      style={{ textDecoration: 'none', color: 'inherit' }}
                    >
                      <Code style={{ cursor: 'pointer' }}>{r.version}</Code>
                    </Link>
                  </Table.Td>
                  <Table.Td>
                    <Text size="sm" c="dimmed">
                      {new Date(r.createdAt).toLocaleString()}
                    </Text>
                  </Table.Td>
                  <Table.Td style={{ textAlign: 'right' }}>
                    <Group gap="xs" justify="flex-end">
                      <ActionIcon
                        variant="subtle"
                        aria-label={t('releases.editAria', { version: r.version })}
                        onClick={() => openEdit(r)}
                      >
                        <IconPencil size={16} />
                      </ActionIcon>
                      <ActionIcon
                        variant="subtle"
                        color="red"
                        aria-label={t('releases.deleteAria', { version: r.version })}
                        onClick={() => setConfirmDelete(r)}
                      >
                        <IconTrash size={16} />
                      </ActionIcon>
                    </Group>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Card>
      )}

      <Modal
        opened={editing !== null}
        onClose={close}
        title={isEditMode ? t('releases.editTitle') : t('releases.createTitle')}
      >
        <form
          onSubmit={form.onSubmit((values) => saveMutation.mutate(values))}
          data-testid="release-form"
        >
          <Stack>
            <Text size="sm" c="dimmed">
              {t('releases.versionHint')}
            </Text>
            <TextInput
              label={t('releases.version')}
              placeholder="1.2.3"
              {...form.getInputProps('version')}
              disabled={saveMutation.isPending}
            />
            {error ? (
              <Alert color="red" variant="light">
                {error}
              </Alert>
            ) : null}
            <Group justify="flex-end">
              <Button variant="default" onClick={close} disabled={saveMutation.isPending}>
                {t('releases.cancel')}
              </Button>
              <Button type="submit" loading={saveMutation.isPending}>
                {isEditMode ? t('releases.save') : t('releases.create')}
              </Button>
            </Group>
          </Stack>
        </form>
      </Modal>

      <Modal
        opened={confirmDelete !== null}
        onClose={() => setConfirmDelete(null)}
        title={confirmDelete ? t('releases.deleteTitle', { version: confirmDelete.version }) : ''}
      >
        <Stack>
          <Text size="sm">{t('releases.deleteBody')}</Text>
          <Group justify="flex-end">
            <Button variant="default" onClick={() => setConfirmDelete(null)}>
              {t('releases.cancel')}
            </Button>
            <Button
              color="red"
              loading={deleteMutation.isPending}
              onClick={() => confirmDelete && deleteMutation.mutate(confirmDelete.id)}
            >
              {t('releases.deleteConfirm')}
            </Button>
          </Group>
        </Stack>
      </Modal>
    </Stack>
  );
}
