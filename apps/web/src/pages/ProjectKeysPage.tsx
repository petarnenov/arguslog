import {
  ActionIcon,
  Alert,
  Badge,
  Button,
  Card,
  Center,
  Code,
  CopyButton,
  Group,
  Loader,
  Modal,
  Stack,
  Switch,
  Table,
  Text,
  Title,
  Tooltip,
} from '@mantine/core';
import { IconRefresh, IconTrash } from '@tabler/icons-react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useParams } from 'react-router';

import { ApiError } from '../api/client';
import { createDsn, revokeDsn, type Dsn, type DsnSummary } from '../api/keys';
import { useDsns } from '../api/queries';

function describeApiError(err: unknown): string {
  return err instanceof ApiError ? (err.problem.detail ?? err.problem.title) : String(err);
}

export function ProjectKeysPage() {
  const { projectId: projectIdParam } = useParams();
  const { t } = useTranslation();
  const queryClient = useQueryClient();

  const projectId = projectIdParam ? Number(projectIdParam) : undefined;
  const [includeRevoked, setIncludeRevoked] = useState(false);
  const dsnsQuery = useDsns(projectId, { includeRevoked });

  const [revealedKey, setRevealedKey] = useState<Dsn | null>(null);
  const [revokeTarget, setRevokeTarget] = useState<DsnSummary | null>(null);
  const [rotateTarget, setRotateTarget] = useState<DsnSummary | null>(null);
  const [generateError, setGenerateError] = useState<string | null>(null);
  const [revokeError, setRevokeError] = useState<string | null>(null);
  const [rotateError, setRotateError] = useState<string | null>(null);

  const generateMutation = useMutation({
    mutationFn: () => {
      if (projectId == null) throw new Error('projectId missing');
      return createDsn(projectId);
    },
    onSuccess: async (dsn) => {
      if (projectId != null) {
        await queryClient.invalidateQueries({ queryKey: ['dsns', projectId] });
      }
      setGenerateError(null);
      setRevealedKey(dsn);
    },
    onError: (err) => setGenerateError(describeApiError(err)),
  });

  const revokeMutation = useMutation({
    mutationFn: (key: DsnSummary) => {
      if (projectId == null) throw new Error('projectId missing');
      return revokeDsn(projectId, key.id);
    },
    onSuccess: async () => {
      if (projectId != null) {
        // Invalidate both cache shapes — the query-key carries `includeRevoked` so we have to
        // touch the prefix to refetch whichever toggle the user is currently on.
        await queryClient.invalidateQueries({ queryKey: ['dsns', projectId] });
      }
      setRevokeTarget(null);
      setRevokeError(null);
    },
    onError: (err) => setRevokeError(describeApiError(err)),
  });

  // Rotate = mint a new key first, then revoke the old. Order matters: if revoke runs first and
  // a misconfigured pipeline is still using the old key, every event between the revoke and the
  // new key landing in their config is rejected. New-then-revoke gives the operator the new DSN
  // first; revoking the old one is a separate confirm step inside the reveal modal.
  const rotateMutation = useMutation({
    mutationFn: async (oldKey: DsnSummary) => {
      if (projectId == null) throw new Error('projectId missing');
      const fresh = await createDsn(projectId);
      return { fresh, oldKey };
    },
    onSuccess: async ({ fresh }) => {
      if (projectId != null) {
        await queryClient.invalidateQueries({ queryKey: ['dsns', projectId] });
      }
      setRotateError(null);
      setRotateTarget(null);
      // Hand the new DSN to the existing reveal modal — operator sees the new string once with
      // a "revoke the old one" prompt next to it.
      setRevealedKey(fresh);
    },
    onError: (err) => setRotateError(describeApiError(err)),
  });

  if (projectId == null || Number.isNaN(projectId)) {
    return (
      <Stack>
        <Title order={3}>{t('projectKeys.title')}</Title>
        <Text c="dimmed">{t('issues.invalidProjectId')}</Text>
      </Stack>
    );
  }

  return (
    <Stack>
      <Group justify="space-between" align="flex-start">
        <Stack gap={4}>
          <Group gap="sm" align="center">
            <Title order={3}>{t('projectKeys.title')}</Title>
            <Badge color="blue" variant="light" data-testid="dsn-credential-badge">
              {t('projectKeys.badge')}
            </Badge>
          </Group>
          <Text c="dimmed" size="sm" maw={680}>
            {t('projectKeys.subtitle')}
          </Text>
          <Text c="dimmed" size="xs" maw={680}>
            {t('projectKeys.useHint')}
          </Text>
        </Stack>
        <Group gap="sm" align="center">
          <Switch
            checked={includeRevoked}
            onChange={(e) => setIncludeRevoked(e.currentTarget.checked)}
            label={t('projectKeys.showRevoked')}
            data-testid="show-revoked-toggle"
          />
          <Button onClick={() => generateMutation.mutate()} loading={generateMutation.isPending}>
            {t('projectKeys.generate')}
          </Button>
        </Group>
      </Group>

      {generateError ? (
        <Alert color="red" variant="light">
          {generateError}
        </Alert>
      ) : null}
      {rotateError ? (
        <Alert color="red" variant="light">
          {rotateError}
        </Alert>
      ) : null}

      {dsnsQuery.isLoading ? (
        <Center mih={120}>
          <Loader size="sm" />
        </Center>
      ) : dsnsQuery.isError ? (
        <Alert color="red" variant="light">
          {t('projectKeys.loadFailed')}
        </Alert>
      ) : dsnsQuery.data && dsnsQuery.data.length === 0 ? (
        <Card withBorder padding="lg" radius="md">
          <Text c="dimmed">{t('projectKeys.empty')}</Text>
        </Card>
      ) : (
        <Card withBorder padding={0} radius="md">
          <Table>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('projectKeys.colId')}</Table.Th>
                <Table.Th>{t('projectKeys.colPublic')}</Table.Th>
                <Table.Th>{t('projectKeys.colCreated')}</Table.Th>
                <Table.Th style={{ width: 120 }}>{t('projectKeys.colActions')}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {dsnsQuery.data?.map((key) => (
                <Table.Tr
                  key={key.id}
                  data-testid={`dsn-row-${key.id}`}
                  style={key.active ? undefined : { opacity: 0.55 }}
                >
                  <Table.Td>
                    <Group gap="xs">
                      <Text size="sm" ff="monospace">
                        {key.id}
                      </Text>
                      <Badge
                        color={key.active ? 'green' : 'gray'}
                        variant="light"
                        size="sm"
                        data-testid={`dsn-status-${key.id}`}
                      >
                        {t(key.active ? 'projectKeys.active' : 'projectKeys.revoked')}
                      </Badge>
                    </Group>
                  </Table.Td>
                  <Table.Td>
                    <Text size="sm" ff="monospace">
                      {key.dsnPublic}
                    </Text>
                  </Table.Td>
                  <Table.Td>
                    <Text size="sm" c="dimmed">
                      {new Date(key.createdAt).toLocaleString()}
                    </Text>
                  </Table.Td>
                  <Table.Td>
                    {key.active ? (
                      <Group gap={4}>
                        <Tooltip label={t('projectKeys.rotateAria', { publicKey: key.dsnPublic })}>
                          <ActionIcon
                            variant="subtle"
                            color="blue"
                            aria-label={t('projectKeys.rotateAria', {
                              publicKey: key.dsnPublic,
                            })}
                            data-testid={`dsn-rotate-${key.id}`}
                            onClick={() => {
                              setRotateError(null);
                              setRotateTarget(key);
                            }}
                          >
                            <IconRefresh size={16} />
                          </ActionIcon>
                        </Tooltip>
                        <ActionIcon
                          variant="subtle"
                          color="red"
                          aria-label={t('projectKeys.revokeAria', { publicKey: key.dsnPublic })}
                          data-testid={`dsn-revoke-${key.id}`}
                          onClick={() => {
                            setRevokeError(null);
                            setRevokeTarget(key);
                          }}
                        >
                          <IconTrash size={16} />
                        </ActionIcon>
                      </Group>
                    ) : (
                      // Revoked rows are read-only — no rotate (already inactive), no revoke
                      // (idempotent endpoint would 409 anyway).
                      <Text size="xs" c="dimmed">
                        —
                      </Text>
                    )}
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Card>
      )}

      <Modal
        opened={revealedKey !== null}
        onClose={() => setRevealedKey(null)}
        title={t('projectKeys.revealedTitle')}
        size="lg"
        closeOnClickOutside={false}
        closeOnEscape={false}
      >
        {revealedKey ? (
          <Stack>
            <Alert color="yellow" variant="light">
              {t('projectKeys.revealedHint')}
            </Alert>
            <Code block>{revealedKey.dsn}</Code>
            <Group justify="flex-end">
              <CopyButton value={revealedKey.dsn}>
                {({ copied, copy }) => (
                  <Button onClick={copy} variant="default">
                    {copied ? t('projectKeys.revealedCopied') : t('projectKeys.revealedCopy')}
                  </Button>
                )}
              </CopyButton>
              <Button onClick={() => setRevealedKey(null)}>{t('projectKeys.revealedAck')}</Button>
            </Group>
          </Stack>
        ) : null}
      </Modal>

      <Modal
        opened={rotateTarget !== null}
        onClose={() => {
          if (!rotateMutation.isPending) {
            setRotateTarget(null);
            setRotateError(null);
          }
        }}
        title={t('projectKeys.rotateTitle')}
        size="md"
      >
        <Stack>
          <Text size="sm">{t('projectKeys.rotateBody')}</Text>
          {rotateTarget ? (
            <Text size="xs" c="dimmed" ff="monospace">
              {rotateTarget.dsnPublic}
            </Text>
          ) : null}
          <Alert color="yellow" variant="light">
            {t('projectKeys.rotateHint')}
          </Alert>
          <Group justify="flex-end">
            <Button
              variant="default"
              onClick={() => setRotateTarget(null)}
              disabled={rotateMutation.isPending}
            >
              {t('projectKeys.rotateCancel')}
            </Button>
            <Button
              color="blue"
              loading={rotateMutation.isPending}
              onClick={() => rotateTarget && rotateMutation.mutate(rotateTarget)}
            >
              {t('projectKeys.rotateConfirm')}
            </Button>
          </Group>
        </Stack>
      </Modal>

      <Modal
        opened={revokeTarget !== null}
        onClose={() => {
          if (!revokeMutation.isPending) {
            setRevokeTarget(null);
            setRevokeError(null);
          }
        }}
        title={t('projectKeys.revokeTitle')}
        size="md"
      >
        <Stack>
          <Text size="sm">{t('projectKeys.revokeBody')}</Text>
          {revokeTarget ? (
            <Text size="xs" c="dimmed" ff="monospace">
              {revokeTarget.dsnPublic}
            </Text>
          ) : null}
          {revokeError ? (
            <Alert color="red" variant="light">
              {revokeError}
            </Alert>
          ) : null}
          <Group justify="flex-end">
            <Button
              variant="default"
              onClick={() => setRevokeTarget(null)}
              disabled={revokeMutation.isPending}
            >
              {t('projectKeys.revokeCancel')}
            </Button>
            <Button
              color="red"
              loading={revokeMutation.isPending}
              onClick={() => revokeTarget && revokeMutation.mutate(revokeTarget)}
            >
              {t('projectKeys.revokeConfirm')}
            </Button>
          </Group>
        </Stack>
      </Modal>
    </Stack>
  );
}
