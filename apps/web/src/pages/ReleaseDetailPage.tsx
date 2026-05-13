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
  IconCheck,
  IconChevronRight,
  IconCloudUpload,
  IconCopy,
  IconFileCode,
} from '@tabler/icons-react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, Navigate, useParams } from 'react-router';

import { ApiError } from '../api/client';
import { queryKeys, useRelease, useSourceMaps } from '../api/queries';
import {
  createSourceMapUpload,
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
        </Group>
        <Text c="dimmed" size="sm">
          {t('releaseDetail.createdAt', { when: formatter.format(new Date(release.createdAt)) })}
        </Text>
      </Stack>

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
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {sourceMaps.map((sm) => (
                  <SourceMapRow key={sm.id} artifact={sm} formatter={formatter} />
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

function formatBytes(n: number): string {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  if (n < 1024 * 1024 * 1024) return `${(n / (1024 * 1024)).toFixed(1)} MB`;
  return `${(n / (1024 * 1024 * 1024)).toFixed(2)} GB`;
}

function SourceMapRow({
  artifact,
  formatter,
}: {
  artifact: SourceMapArtifact;
  formatter: Intl.DateTimeFormat;
}) {
  const shortSha = artifact.sha256.slice(0, 12);
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
