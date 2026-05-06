import {
  ActionIcon,
  Alert,
  Button,
  Code,
  CopyButton,
  Group,
  Loader,
  Modal,
  Stack,
  Table,
  Text,
  TextInput,
  Title,
  Tooltip,
} from '@mantine/core';
import { IconCheck, IconCopy, IconTrash } from '@tabler/icons-react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';

import { ApiError } from '../api/client';
import { queryKeys, useMyTokens } from '../api/queries';
import { createMyToken, revokeMyToken, type PersonalAccessToken } from '../api/tokens';

export function PersonalAccessTokensPage() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const tokens = useMyTokens();

  const [name, setName] = useState('');
  const [issued, setIssued] = useState<PersonalAccessToken | null>(null);
  const [revokeTarget, setRevokeTarget] = useState<PersonalAccessToken | null>(null);

  const createMutation = useMutation({
    mutationFn: () => createMyToken({ name: name.trim() }),
    onSuccess: async (created) => {
      setIssued(created);
      setName('');
      await queryClient.invalidateQueries({ queryKey: queryKeys.myTokens() });
    },
  });

  const revokeMutation = useMutation({
    mutationFn: (id: number) => revokeMyToken(id),
    onSuccess: async () => {
      setRevokeTarget(null);
      await queryClient.invalidateQueries({ queryKey: queryKeys.myTokens() });
    },
  });

  const createError = errorMessage(createMutation.error);
  const revokeError = errorMessage(revokeMutation.error);
  const trimmed = name.trim();
  const canCreate = trimmed.length >= 2 && !createMutation.isPending;

  return (
    <Stack maw={900}>
      <Title order={3}>{t('tokens.title')}</Title>
      <Text size="sm" c="dimmed">
        {t('tokens.intro')}
      </Text>

      <form
        onSubmit={(e) => {
          e.preventDefault();
          if (canCreate) createMutation.mutate();
        }}
      >
        <Group align="flex-end">
          <TextInput
            label={t('tokens.nameLabel')}
            placeholder={t('tokens.namePlaceholder')}
            value={name}
            onChange={(e) => setName(e.currentTarget.value)}
            disabled={createMutation.isPending}
            style={{ flex: 1 }}
            data-testid="pat-name-input"
          />
          <Button
            type="submit"
            loading={createMutation.isPending}
            disabled={!canCreate}
            data-testid="pat-create-button"
          >
            {t('tokens.create')}
          </Button>
        </Group>
      </form>

      {createError && (
        <Alert color="red" variant="light">
          {createError}
        </Alert>
      )}

      {issued?.token && (
        <Alert color="green" variant="light" data-testid="pat-issued">
          <Stack gap="xs">
            <Text size="sm" fw={500}>
              {t('tokens.issuedTitle', { name: issued.name })}
            </Text>
            <Text size="xs" c="dimmed">
              {t('tokens.issuedHint')}
            </Text>
            <Group gap="xs" wrap="nowrap">
              <Code style={{ flex: 1, overflowX: 'auto' }} data-testid="pat-plaintext">
                {issued.token}
              </Code>
              <CopyButton value={issued.token} timeout={1500}>
                {({ copied, copy }) => (
                  <Tooltip label={copied ? t('tokens.copied') : t('tokens.copy')}>
                    <ActionIcon
                      variant="light"
                      color={copied ? 'teal' : 'blue'}
                      onClick={copy}
                      aria-label={t('tokens.copy')}
                    >
                      {copied ? <IconCheck size={16} /> : <IconCopy size={16} />}
                    </ActionIcon>
                  </Tooltip>
                )}
              </CopyButton>
            </Group>
          </Stack>
        </Alert>
      )}

      {tokens.isLoading ? (
        <Group p="md">
          <Loader size="sm" />
        </Group>
      ) : tokens.isError ? (
        <Alert color="red" variant="light">
          {t('errors.generic')}
        </Alert>
      ) : !tokens.data || tokens.data.length === 0 ? (
        <Text c="dimmed">{t('tokens.empty')}</Text>
      ) : (
        <Table highlightOnHover withTableBorder data-testid="tokens-table">
          <Table.Thead>
            <Table.Tr>
              <Table.Th>{t('tokens.colName')}</Table.Th>
              <Table.Th>{t('tokens.colPrefix')}</Table.Th>
              <Table.Th>{t('tokens.colCreated')}</Table.Th>
              <Table.Th>{t('tokens.colLastUsed')}</Table.Th>
              <Table.Th aria-label={t('tokens.colActions')} />
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {tokens.data.map((tok) => (
              <Table.Tr key={tok.id}>
                <Table.Td>{tok.name}</Table.Td>
                <Table.Td>
                  <Code>{tok.prefix}…</Code>
                </Table.Td>
                <Table.Td>{formatDate(tok.createdAt)}</Table.Td>
                <Table.Td>
                  {tok.lastUsedAt ? formatDate(tok.lastUsedAt) : t('tokens.neverUsed')}
                </Table.Td>
                <Table.Td>
                  <ActionIcon
                    color="red"
                    variant="subtle"
                    onClick={() => setRevokeTarget(tok)}
                    aria-label={t('tokens.revokeAria', { name: tok.name })}
                  >
                    <IconTrash size={16} />
                  </ActionIcon>
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}

      <Modal
        opened={revokeTarget !== null}
        onClose={() => {
          if (!revokeMutation.isPending) setRevokeTarget(null);
        }}
        title={t('tokens.revokeTitle', { name: revokeTarget?.name ?? '' })}
        size="md"
      >
        <Stack>
          <Text size="sm">{t('tokens.revokeBody')}</Text>
          {revokeError && (
            <Alert color="red" variant="light">
              {revokeError}
            </Alert>
          )}
          <Group justify="flex-end">
            <Button
              variant="default"
              onClick={() => setRevokeTarget(null)}
              disabled={revokeMutation.isPending}
            >
              {t('tokens.revokeCancel')}
            </Button>
            <Button
              color="red"
              loading={revokeMutation.isPending}
              onClick={() => revokeTarget && revokeMutation.mutate(revokeTarget.id)}
              data-testid="pat-revoke-confirm"
            >
              {t('tokens.revokeConfirm')}
            </Button>
          </Group>
        </Stack>
      </Modal>
    </Stack>
  );
}

function errorMessage(err: unknown): string | null {
  if (!err) return null;
  if (err instanceof ApiError) return err.problem.detail ?? err.problem.title;
  return String(err);
}

function formatDate(iso: string): string {
  return new Date(iso).toLocaleString();
}
