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
  Table,
  Text,
  Title,
} from '@mantine/core';
import { IconTrash } from '@tabler/icons-react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useParams } from 'react-router';

import { ApiError } from '../api/client';
import { createDsn, revokeDsn, type Dsn, type DsnSummary } from '../api/keys';
import { queryKeys, useDsns } from '../api/queries';

function describeApiError(err: unknown): string {
  return err instanceof ApiError ? (err.problem.detail ?? err.problem.title) : String(err);
}

export function ProjectKeysPage() {
  const { projectId: projectIdParam } = useParams();
  const { t } = useTranslation();
  const queryClient = useQueryClient();

  const projectId = projectIdParam ? Number(projectIdParam) : undefined;
  const dsnsQuery = useDsns(projectId);

  const [revealedKey, setRevealedKey] = useState<Dsn | null>(null);
  const [revokeTarget, setRevokeTarget] = useState<DsnSummary | null>(null);
  const [generateError, setGenerateError] = useState<string | null>(null);
  const [revokeError, setRevokeError] = useState<string | null>(null);

  const generateMutation = useMutation({
    mutationFn: () => {
      if (projectId == null) throw new Error('projectId missing');
      return createDsn(projectId);
    },
    onSuccess: async (dsn) => {
      if (projectId != null) {
        await queryClient.invalidateQueries({ queryKey: queryKeys.dsns(projectId) });
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
        await queryClient.invalidateQueries({ queryKey: queryKeys.dsns(projectId) });
      }
      setRevokeTarget(null);
      setRevokeError(null);
    },
    onError: (err) => setRevokeError(describeApiError(err)),
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
          <Title order={3}>{t('projectKeys.title')}</Title>
          <Text c="dimmed" size="sm" maw={680}>
            {t('projectKeys.subtitle')}
          </Text>
        </Stack>
        <Button onClick={() => generateMutation.mutate()} loading={generateMutation.isPending}>
          {t('projectKeys.generate')}
        </Button>
      </Group>

      {generateError ? (
        <Alert color="red" variant="light">
          {generateError}
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
                <Table.Tr key={key.id} data-testid={`dsn-row-${key.id}`}>
                  <Table.Td>
                    <Group gap="xs">
                      <Text size="sm" ff="monospace">
                        {key.id}
                      </Text>
                      <Badge color="green" variant="light" size="sm">
                        {t('projectKeys.active')}
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
                    <ActionIcon
                      variant="subtle"
                      color="red"
                      aria-label={t('projectKeys.revokeAria', { publicKey: key.dsnPublic })}
                      onClick={() => {
                        setRevokeError(null);
                        setRevokeTarget(key);
                      }}
                    >
                      <IconTrash size={16} />
                    </ActionIcon>
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
