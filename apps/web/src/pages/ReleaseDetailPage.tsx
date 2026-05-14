import {
  Alert,
  Badge,
  Breadcrumbs,
  Button,
  Card,
  Center,
  Code,
  CopyButton,
  FileInput,
  Group,
  Loader,
  Modal,
  Progress,
  Stack,
  Table,
  Text,
  TextInput,
  Title,
  Tooltip,
} from '@mantine/core';
import {
  IconAlertTriangle,
  IconCheck,
  IconChevronRight,
  IconCloudUpload,
  IconCopy,
  IconFileCode,
  IconTrash,
} from '@tabler/icons-react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, Navigate, useParams } from 'react-router';

import { ApiError } from '../api/client';
import { queryKeys, useRelease, useReleaseIssues, useSourceMaps } from '../api/queries';
import type { Issue } from '../api/issues';
import {
  createSourceMapUpload,
  deleteSourceMap,
  uploadFileToPresignedUrl,
  type SourceMapArtifact,
} from '../api/sourcemaps';
import { sha256OfFile } from '../lib/sha256Browser';

export function ReleaseDetailPage() {
  const { t, i18n } = useTranslation();
  const queryClient = useQueryClient();
  const { orgSlug, projectId: rawProjectId, releaseId: rawReleaseId } = useParams();

  const projectId = Number(rawProjectId);
  const releaseId = Number(rawReleaseId);
  const valid =
    Number.isFinite(projectId) && projectId > 0 && Number.isFinite(releaseId) && releaseId > 0;

  const releaseQ = useRelease(projectId, releaseId, { enabled: valid });
  const sourceMapsQ = useSourceMaps(projectId, releaseId, { enabled: valid });
  const releaseIssuesQ = useReleaseIssues(projectId, releaseId, { enabled: valid });

  const [uploadOpen, setUploadOpen] = useState(false);

  const formatter = useMemo(
    () =>
      new Intl.DateTimeFormat(i18n.language || 'en', {
        dateStyle: 'medium',
        timeStyle: 'short',
      }),
    [i18n.language],
  );

  if (!valid) return <Navigate to="/orgs" replace />;

  if (releaseQ.isLoading) {
    return (
      <Center py="xl">
        <Loader />
      </Center>
    );
  }

  if (releaseQ.isError || !releaseQ.data) {
    return (
      <Stack>
        <Title order={3}>{t('releaseDetail.notFound')}</Title>
        <Button component={Link} to={`/orgs/${orgSlug}/projects/${projectId}/releases`} variant="light">
          {t('releaseDetail.backToList')}
        </Button>
      </Stack>
    );
  }

  const release = releaseQ.data;
  const sourceMaps = sourceMapsQ.data ?? [];

  return (
    <Stack maw={960}>
      <Breadcrumbs separator={<IconChevronRight size={14} />}>
        <Link to={`/orgs/${orgSlug}/projects/${projectId}/releases`}>{t('releases.title')}</Link>
        <Text>{release.version}</Text>
      </Breadcrumbs>

      <Stack gap={4}>
        <Group gap="sm" align="center">
          <Title order={3}>{release.version}</Title>
          <Badge variant="light">{t('releaseDetail.releaseBadge')}</Badge>
          {release.deployStage && (
            <Badge variant="filled" color="blue" data-testid="release-deploy-stage">
              {release.deployStage}
            </Badge>
          )}
        </Group>
        <Text c="dimmed" size="sm">
          {t('releaseDetail.createdAt', { when: formatter.format(new Date(release.createdAt)) })}
        </Text>
      </Stack>

      <ReleaseMetadataCard release={release} formatter={formatter} />

      {release.changelog && (
        <Card withBorder padding="md" data-testid="release-changelog">
          <Stack gap="sm">
            <Title order={5}>{t('releaseDetail.changelogTitle')}</Title>
            <Text
              size="sm"
              style={{ whiteSpace: 'pre-wrap', fontFamily: 'var(--mantine-font-family-monospace)' }}
            >
              {release.changelog}
            </Text>
          </Stack>
        </Card>
      )}

      <ReleaseIssuesCard
        orgSlug={orgSlug}
        projectId={projectId}
        issues={releaseIssuesQ.data ?? []}
        loading={releaseIssuesQ.isLoading}
        formatter={formatter}
      />

      <Card withBorder padding="md">
        <Stack>
          <Group justify="space-between" align="center">
            <Stack gap={2}>
              <Group gap="sm">
                <IconFileCode size={18} />
                <Title order={5}>{t('releaseDetail.sourceMapsTitle')}</Title>
                <Badge variant="default">{sourceMaps.length}</Badge>
              </Group>
              <Text size="xs" c="dimmed" maw={620}>
                {t('releaseDetail.sourceMapsHint')}
              </Text>
            </Stack>
            <Button
              leftSection={<IconCloudUpload size={14} />}
              onClick={() => setUploadOpen(true)}
              data-testid="upload-sourcemap-button"
            >
              {t('releaseDetail.uploadButton')}
            </Button>
          </Group>

          {sourceMapsQ.isLoading ? (
            <Center py="md">
              <Loader size="sm" />
            </Center>
          ) : sourceMaps.length === 0 ? (
            <Alert variant="light">{t('releaseDetail.empty')}</Alert>
          ) : (
            <Table highlightOnHover striped data-testid="sourcemaps-table">
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>{t('releaseDetail.colPath')}</Table.Th>
                  <Table.Th>{t('releaseDetail.colSize')}</Table.Th>
                  <Table.Th>{t('releaseDetail.colSha')}</Table.Th>
                  <Table.Th>{t('releaseDetail.colUploaded')}</Table.Th>
                  <Table.Th aria-label={t('releaseDetail.colActions')} />
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {sourceMaps.map((sm) => (
                  <SourceMapRow
                    key={sm.id}
                    artifact={sm}
                    formatter={formatter}
                    projectId={projectId}
                    releaseId={releaseId}
                  />
                ))}
              </Table.Tbody>
            </Table>
          )}
        </Stack>
      </Card>

      <UploadSourceMapModal
        opened={uploadOpen}
        onClose={() => setUploadOpen(false)}
        projectId={projectId}
        releaseId={releaseId}
        onUploaded={() => {
          void queryClient.invalidateQueries({
            queryKey: queryKeys.sourceMaps(projectId, releaseId),
          });
        }}
      />
    </Stack>
  );
}

// ── helpers / inner components ──────────────────────────────────────────────

function ReleaseIssuesCard({
  orgSlug,
  projectId,
  issues,
  loading,
  formatter,
}: {
  orgSlug: string | undefined;
  projectId: number;
  issues: Issue[];
  loading: boolean;
  formatter: Intl.DateTimeFormat;
}) {
  const { t } = useTranslation();

  return (
    <Card withBorder padding="md" data-testid="release-issues-card">
      <Stack>
        <Group gap="sm">
          <IconAlertTriangle size={18} />
          <Title order={5}>{t('releaseDetail.issuesTitle')}</Title>
          <Badge variant="filled" color={issues.length > 0 ? 'red' : 'gray'}>
            {issues.length}
          </Badge>
        </Group>
        <Text size="xs" c="dimmed" maw={620}>
          {t('releaseDetail.issuesHint')}
        </Text>
        {loading ? (
          <Center py="md">
            <Loader size="sm" />
          </Center>
        ) : issues.length === 0 ? (
          <Alert variant="light" color="green">
            {t('releaseDetail.issuesEmpty')}
          </Alert>
        ) : (
          <Table highlightOnHover striped data-testid="release-issues-table">
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('releaseDetail.colTitle')}</Table.Th>
                <Table.Th style={{ width: 110 }}>{t('releaseDetail.colLevel')}</Table.Th>
                <Table.Th style={{ width: 90 }}>{t('releaseDetail.colCount')}</Table.Th>
                <Table.Th style={{ width: 180 }}>{t('releaseDetail.colFirstSeen')}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {issues.map((iss) => (
                <Table.Tr key={iss.id} data-testid={`release-issue-row-${iss.id}`}>
                  <Table.Td>
                    <Link
                      to={`/orgs/${orgSlug}/projects/${projectId}/issues/${iss.id}`}
                      style={{ textDecoration: 'none', color: 'inherit' }}
                    >
                      <Text fw={500}>{iss.title}</Text>
                      {iss.culprit && (
                        <Text size="xs" c="dimmed">
                          {iss.culprit}
                        </Text>
                      )}
                    </Link>
                  </Table.Td>
                  <Table.Td>
                    <Badge variant="light" color={levelColor(iss.level)}>
                      {iss.level}
                    </Badge>
                  </Table.Td>
                  <Table.Td>{iss.occurrenceCount.toLocaleString()}</Table.Td>
                  <Table.Td>
                    <Text size="sm" c="dimmed">
                      {formatter.format(new Date(iss.firstSeenAt))}
                    </Text>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        )}
      </Stack>
    </Card>
  );
}

function levelColor(level: Issue['level']): string {
  switch (level) {
    case 'fatal':
      return 'red';
    case 'error':
      return 'orange';
    case 'warning':
      return 'yellow';
    case 'info':
      return 'blue';
    case 'debug':
      return 'gray';
    default:
      return 'gray';
  }
}

function ReleaseMetadataCard({
  release,
  formatter,
}: {
  release: import('../api/releases').Release;
  formatter: Intl.DateTimeFormat;
}) {
  const { t } = useTranslation();
  const rows: Array<[string, React.ReactNode]> = [];
  if (release.releasedAt) {
    rows.push([
      t('releaseDetail.releasedAt'),
      formatter.format(new Date(release.releasedAt)),
    ]);
  }
  if (release.gitRef) rows.push([t('releaseDetail.gitRef'), <Code key="ref">{release.gitRef}</Code>]);
  if (release.gitSha) {
    const short = release.gitSha.length > 12 ? release.gitSha.slice(0, 12) : release.gitSha;
    rows.push([
      t('releaseDetail.gitSha'),
      <Group gap={4} key="sha">
        <Code style={{ fontSize: 12 }}>{short}{release.gitSha.length > 12 ? '…' : ''}</Code>
        <CopyButton value={release.gitSha}>
          {({ copied, copy }) => (
            <Button
              size="compact-xs"
              variant="subtle"
              color={copied ? 'teal' : 'gray'}
              onClick={copy}
              aria-label="Copy git sha"
            >
              {copied ? <IconCheck size={12} /> : <IconCopy size={12} />}
            </Button>
          )}
        </CopyButton>
      </Group>,
    ]);
  }

  if (rows.length === 0) return null;

  return (
    <Card withBorder padding="md" data-testid="release-metadata-card">
      <Stack gap="xs">
        <Title order={6}>{t('releaseDetail.metadataTitle')}</Title>
        <Table withRowBorders={false} verticalSpacing="xs">
          <Table.Tbody>
            {rows.map(([label, value]) => (
              <Table.Tr key={label}>
                <Table.Td style={{ width: 180, color: 'var(--mantine-color-dimmed)' }}>
                  {label}
                </Table.Td>
                <Table.Td>{value}</Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      </Stack>
    </Card>
  );
}

function formatBytes(n: number): string {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  if (n < 1024 * 1024 * 1024) return `${(n / (1024 * 1024)).toFixed(1)} MB`;
  return `${(n / (1024 * 1024 * 1024)).toFixed(2)} GB`;
}

function SourceMapRow({
  artifact,
  formatter,
  projectId,
  releaseId,
}: {
  artifact: SourceMapArtifact;
  formatter: Intl.DateTimeFormat;
  projectId: number;
  releaseId: number;
}) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const shortSha = artifact.sha256.slice(0, 12);

  const deleteMutation = useMutation({
    mutationFn: () => deleteSourceMap(projectId, releaseId, artifact.id),
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: queryKeys.sourceMaps(projectId, releaseId),
      });
    },
  });

  const handleDelete = () => {
    // Inline window.confirm intentionally — the action is destructive but reversible (just re-upload)
    // and the rest of the dashboard uses the same pattern (DeleteReleaseButton on ReleasesPage).
    if (window.confirm(t('releaseDetail.deleteSourceMapConfirm', { path: artifact.originalPath }))) {
      deleteMutation.mutate();
    }
  };

  return (
    <Table.Tr>
      <Table.Td>
        <Code style={{ fontSize: 12 }}>{artifact.originalPath}</Code>
      </Table.Td>
      <Table.Td>{formatBytes(artifact.sizeBytes)}</Table.Td>
      <Table.Td>
        <Tooltip label={artifact.sha256} multiline w={300}>
          <Group gap={4}>
            <Code style={{ fontSize: 11 }}>{shortSha}…</Code>
            <CopyButton value={artifact.sha256}>
              {({ copied, copy }) => (
                <Button
                  size="compact-xs"
                  variant="subtle"
                  color={copied ? 'teal' : 'gray'}
                  onClick={copy}
                  aria-label="Copy sha256"
                >
                  {copied ? <IconCheck size={12} /> : <IconCopy size={12} />}
                </Button>
              )}
            </CopyButton>
          </Group>
        </Tooltip>
      </Table.Td>
      <Table.Td>{formatter.format(new Date(artifact.createdAt))}</Table.Td>
      <Table.Td>
        <Button
          size="compact-xs"
          variant="subtle"
          color="red"
          loading={deleteMutation.isPending}
          onClick={handleDelete}
          aria-label={t('releaseDetail.deleteSourceMap', { path: artifact.originalPath })}
          data-testid={`delete-sourcemap-${artifact.id}`}
        >
          <IconTrash size={14} />
        </Button>
      </Table.Td>
    </Table.Tr>
  );
}

interface UploadModalProps {
  opened: boolean;
  onClose: () => void;
  projectId: number;
  releaseId: number;
  onUploaded: () => void;
}

function UploadSourceMapModal({
  opened,
  onClose,
  projectId,
  releaseId,
  onUploaded,
}: UploadModalProps) {
  const { t } = useTranslation();
  const [file, setFile] = useState<File | null>(null);
  const [originalPath, setOriginalPath] = useState('');
  const [progress, setProgress] = useState(0);
  const [stage, setStage] = useState<'idle' | 'hashing' | 'minting' | 'uploading'>('idle');
  const [error, setError] = useState<string | null>(null);

  // Auto-suggest the original path from the filename: strip a trailing .map (Vite + Webpack
  // emit "bundle.js.map" → "bundle.js"). User can edit before submit.
  useEffect(() => {
    if (!file) return;
    const guessed = file.name.endsWith('.map') ? file.name.slice(0, -4) : file.name;
    setOriginalPath(guessed);
  }, [file]);

  const reset = () => {
    setFile(null);
    setOriginalPath('');
    setProgress(0);
    setStage('idle');
    setError(null);
  };

  const uploadMutation = useMutation({
    mutationFn: async () => {
      if (!file) throw new Error('no file selected');
      const trimmed = originalPath.trim();
      if (!trimmed) throw new Error('originalPath is required');

      setStage('hashing');
      const sha256 = await sha256OfFile(file);

      setStage('minting');
      const created = await createSourceMapUpload(projectId, releaseId, {
        originalPath: trimmed,
        sha256,
        sizeBytes: file.size,
      });

      setStage('uploading');
      await uploadFileToPresignedUrl(created.uploadUrl, file, (loaded, total) =>
        setProgress(Math.round((loaded / total) * 100)),
      );
    },
    onSuccess: () => {
      onUploaded();
      reset();
      onClose();
    },
    onError: (e: unknown) => {
      setStage('idle');
      setError(e instanceof ApiError ? (e.problem.detail ?? e.problem.title) : String(e));
    },
  });

  const busy = uploadMutation.isPending;
  const stageLabel: Record<typeof stage, string> = {
    idle: '',
    hashing: t('releaseDetail.upload.hashing'),
    minting: t('releaseDetail.upload.minting'),
    uploading: t('releaseDetail.upload.uploading', { progress }),
  };

  return (
    <Modal
      opened={opened}
      onClose={() => {
        if (!busy) {
          reset();
          onClose();
        }
      }}
      title={t('releaseDetail.upload.title')}
      size="md"
    >
      <Stack>
        <Text size="sm" c="dimmed">
          {t('releaseDetail.upload.hint')}
        </Text>

        <FileInput
          label={t('releaseDetail.upload.fileLabel')}
          placeholder="bundle.js.map"
          accept=".map,application/json,text/plain"
          value={file}
          onChange={setFile}
          disabled={busy}
          data-testid="upload-file-input"
        />

        <TextInput
          label={t('releaseDetail.upload.pathLabel')}
          description={t('releaseDetail.upload.pathHint')}
          placeholder="bundle.js"
          value={originalPath}
          onChange={(e) => setOriginalPath(e.currentTarget.value)}
          disabled={busy}
          data-testid="upload-path-input"
        />

        {stage !== 'idle' && (
          <Stack gap={4}>
            <Text size="xs" c="dimmed" data-testid="upload-stage">
              {stageLabel[stage]}
            </Text>
            <Progress
              value={stage === 'uploading' ? progress : stage === 'minting' ? 5 : 1}
              animated={busy}
              striped={busy}
            />
          </Stack>
        )}

        {error && (
          <Alert color="red" variant="light" withCloseButton onClose={() => setError(null)}>
            {error}
          </Alert>
        )}

        <Group justify="flex-end">
          <Button
            variant="default"
            disabled={busy}
            onClick={() => {
              reset();
              onClose();
            }}
          >
            {t('releaseDetail.upload.cancel')}
          </Button>
          <Button
            loading={busy}
            disabled={!file || originalPath.trim().length === 0}
            onClick={() => uploadMutation.mutate()}
            data-testid="upload-submit"
          >
            {t('releaseDetail.upload.submit')}
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}
