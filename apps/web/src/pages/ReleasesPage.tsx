import {
  ActionIcon,
  Alert,
  Button,
  Card,
  Center,
  Code,
  Divider,
  Group,
  Loader,
  Modal,
  Stack,
  Table,
  Text,
  Textarea,
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
  /** Local datetime string (`yyyy-MM-ddTHH:mm`) — converted to UTC ISO on submit. */
  releasedAtLocal: string;
  gitSha: string;
  gitRef: string;
  deployStage: string;
  changelog: string;
}

/** Converts a stored ISO-8601 UTC string into `<input type="datetime-local">` value space. */
function isoToLocalInput(iso: string | null): string {
  if (!iso) return '';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  // datetime-local wants yyyy-MM-ddTHH:mm in *local* tz; ISO offsets are pre-handled by Date.
  const pad = (n: number) => n.toString().padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** Inverse of {@link isoToLocalInput} — empty input → null, otherwise local→ISO UTC. */
function localInputToIso(local: string): string | null {
  const trimmed = local.trim();
  if (!trimmed) return null;
  const d = new Date(trimmed);
  if (Number.isNaN(d.getTime())) return null;
  return d.toISOString();
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
    initialValues: {
      version: '',
      releasedAtLocal: '',
      gitSha: '',
      gitRef: '',
      deployStage: '',
      changelog: '',
    },
    validate: {
      version: (v) => (v.trim().length < 1 ? t('releases.errorVersion') : null),
    },
  });

  function openCreate() {
    setError(null);
    form.setValues({
      version: '',
      releasedAtLocal: '',
      gitSha: '',
      gitRef: '',
      deployStage: '',
      changelog: '',
    });
    setEditing('new');
  }

  function openEdit(r: Release) {
    setError(null);
    form.setValues({
      version: r.version,
      releasedAtLocal: isoToLocalInput(r.releasedAt),
      gitSha: r.gitSha ?? '',
      gitRef: r.gitRef ?? '',
      deployStage: r.deployStage ?? '',
      changelog: r.changelog ?? '',
    });
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
      // Trim every string then normalise blank → null so the API receives "no value" rather than
      // the empty string for fields the operator left untouched. PUT semantics: blank clears.
      const blankToNull = (s: string): string | null => {
        const t = s.trim();
        return t === '' ? null : t;
      };
      const payload = {
        version: values.version.trim(),
        releasedAt: localInputToIso(values.releasedAtLocal),
        gitSha: blankToNull(values.gitSha),
        gitRef: blankToNull(values.gitRef),
        deployStage: blankToNull(values.deployStage),
        changelog: blankToNull(values.changelog),
      };
      if (isEditMode) {
        return updateRelease(projectId, editing.id, payload);
      }
      return createRelease(projectId, payload);
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

            <Divider label={t('releases.metadataDivider')} labelPosition="left" />

            <TextInput
              label={t('releases.releasedAtLabel')}
              description={t('releases.releasedAtHint')}
              type="datetime-local"
              {...form.getInputProps('releasedAtLocal')}
              disabled={saveMutation.isPending}
              data-testid="release-released-at"
            />
            <Group grow>
              <TextInput
                label={t('releases.gitRefLabel')}
                placeholder="main"
                {...form.getInputProps('gitRef')}
                disabled={saveMutation.isPending}
                data-testid="release-git-ref"
              />
              <TextInput
                label={t('releases.gitShaLabel')}
                placeholder="abcdef1"
                {...form.getInputProps('gitSha')}
                disabled={saveMutation.isPending}
                data-testid="release-git-sha"
              />
            </Group>
            <TextInput
              label={t('releases.deployStageLabel')}
              description={t('releases.deployStageHint')}
              placeholder="production"
              {...form.getInputProps('deployStage')}
              disabled={saveMutation.isPending}
              data-testid="release-deploy-stage"
            />
            <Textarea
              label={t('releases.changelogLabel')}
              description={t('releases.changelogHint')}
              autosize
              minRows={3}
              maxRows={10}
              {...form.getInputProps('changelog')}
              disabled={saveMutation.isPending}
              data-testid="release-changelog"
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
