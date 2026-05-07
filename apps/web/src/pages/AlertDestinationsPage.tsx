import {
  ActionIcon,
  Alert,
  Badge,
  Button,
  Card,
  Center,
  Group,
  Loader,
  Modal,
  Select,
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
import { useParams } from 'react-router';

import {
  type AlertDestination,
  createAlertDestination,
  deleteAlertDestination,
  type DestinationKind,
  updateAlertDestination,
} from '../api/alerts';
import { ApiError } from '../api/client';
import { useAlertDestinations, useMyOrgs, queryKeys } from '../api/queries';

const KIND_OPTIONS: { value: DestinationKind; label: string }[] = [
  { value: 'telegram', label: 'Telegram' },
  { value: 'email', label: 'Email' },
  { value: 'slack', label: 'Slack' },
  { value: 'webhook', label: 'Webhook' },
];

const CONFIG_PLACEHOLDERS: Record<DestinationKind, string> = {
  telegram: '{\n  "chatId": "-1001234567890"\n}',
  email: '{\n  "to": "ops@example.com"\n}',
  slack: '{\n  "webhookUrl": "https://hooks.slack.com/services/T00/B00/XXX"\n}',
  webhook: '{\n  "url": "https://hooks.example.com/arguslog"\n}',
};

interface DraftValues {
  kind: DestinationKind;
  name: string;
  config: string;
}

const EMPTY_DRAFT: DraftValues = { kind: 'telegram', name: '', config: '' };

export function AlertDestinationsPage() {
  const { orgSlug } = useParams();
  const { t } = useTranslation();
  const queryClient = useQueryClient();

  const orgsQuery = useMyOrgs();
  const org = orgsQuery.data?.find((o) => o.slug === orgSlug);
  const destinationsQuery = useAlertDestinations(org?.id);

  const [editing, setEditing] = useState<AlertDestination | 'new' | null>(null);
  const [error, setError] = useState<string | null>(null);

  const form = useForm<DraftValues>({
    initialValues: EMPTY_DRAFT,
    validate: {
      name: (v) => (v.trim().length < 1 ? t('alertDestinations.errorName') : null),
      config: (v) => {
        if (!v.trim()) return t('alertDestinations.errorConfigRequired');
        try {
          const parsed = JSON.parse(v);
          if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
            return t('alertDestinations.errorConfigShape');
          }
          return null;
        } catch {
          return t('alertDestinations.errorConfigJson');
        }
      },
    },
  });

  const isEditMode = editing && editing !== 'new';

  function openCreate() {
    setError(null);
    form.setValues(EMPTY_DRAFT);
    setEditing('new');
  }

  function openEdit(d: AlertDestination) {
    setError(null);
    // We never get the config back from the server (write-only secret).
    // The user must re-type it to save changes — flagged in the form.
    form.setValues({ kind: d.kind, name: d.name, config: '' });
    setEditing(d);
  }

  function close() {
    setEditing(null);
    form.reset();
    setError(null);
  }

  const saveMutation = useMutation({
    mutationFn: async (values: DraftValues) => {
      if (!org) throw new Error('org missing');
      const config = JSON.parse(values.config) as unknown;
      if (isEditMode) {
        return updateAlertDestination(org.id, editing.id, {
          kind: values.kind,
          name: values.name,
          config,
        });
      }
      return createAlertDestination(org.id, {
        kind: values.kind,
        name: values.name,
        config,
      });
    },
    onSuccess: async () => {
      if (org) {
        await queryClient.invalidateQueries({ queryKey: queryKeys.alertDestinations(org.id) });
      }
      close();
    },
    onError: (err: unknown) => {
      setError(err instanceof ApiError ? (err.problem.detail ?? err.problem.title) : String(err));
    },
  });

  const deleteMutation = useMutation({
    mutationFn: async (id: number) => {
      if (!org) throw new Error('org missing');
      return deleteAlertDestination(org.id, id);
    },
    onSuccess: async () => {
      if (org) {
        await queryClient.invalidateQueries({ queryKey: queryKeys.alertDestinations(org.id) });
      }
    },
  });

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
        <Title order={3}>{t('alertDestinations.title')}</Title>
        <Text c="dimmed">{t('projects.orgNotFound')}</Text>
      </Stack>
    );
  }

  return (
    <Stack>
      <Group justify="space-between">
        <Title order={3}>{t('alertDestinations.title')}</Title>
        <Button leftSection={<IconPlus size={16} />} onClick={openCreate}>
          {t('alertDestinations.new')}
        </Button>
      </Group>

      {destinationsQuery.isLoading ? (
        <Center mih={120}>
          <Loader size="sm" />
        </Center>
      ) : destinationsQuery.data && destinationsQuery.data.length === 0 ? (
        <Card withBorder padding="lg">
          <Text c="dimmed">{t('alertDestinations.empty')}</Text>
        </Card>
      ) : (
        <Card withBorder padding={0}>
          <Table data-testid="alert-destinations-table">
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('alertDestinations.colName')}</Table.Th>
                <Table.Th>{t('alertDestinations.colKind')}</Table.Th>
                <Table.Th style={{ width: 110, textAlign: 'right' }}>
                  {t('alertDestinations.colActions')}
                </Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {destinationsQuery.data?.map((d) => (
                <Table.Tr key={d.id}>
                  <Table.Td>{d.name}</Table.Td>
                  <Table.Td>
                    <Badge variant="light">{d.kind}</Badge>
                  </Table.Td>
                  <Table.Td style={{ textAlign: 'right' }}>
                    <Group gap="xs" justify="flex-end">
                      <ActionIcon
                        variant="subtle"
                        aria-label={t('alertDestinations.editAria', { name: d.name })}
                        onClick={() => openEdit(d)}
                      >
                        <IconPencil size={16} />
                      </ActionIcon>
                      <ActionIcon
                        variant="subtle"
                        color="red"
                        aria-label={t('alertDestinations.deleteAria', { name: d.name })}
                        onClick={() => deleteMutation.mutate(d.id)}
                        loading={deleteMutation.isPending && deleteMutation.variables === d.id}
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
        title={isEditMode ? t('alertDestinations.editTitle') : t('alertDestinations.createTitle')}
        size="lg"
      >
        <form onSubmit={form.onSubmit((values) => saveMutation.mutate(values))}>
          <Stack>
            <Select
              label={t('alertDestinations.kind')}
              data={KIND_OPTIONS}
              value={form.values.kind}
              onChange={(value) =>
                form.setFieldValue('kind', (value ?? 'telegram') as DestinationKind)
              }
              disabled={isEditMode || saveMutation.isPending}
              description={isEditMode ? t('alertDestinations.kindLocked') : undefined}
            />
            <TextInput
              label={t('alertDestinations.name')}
              {...form.getInputProps('name')}
              disabled={saveMutation.isPending}
            />
            <Textarea
              label={t('alertDestinations.config')}
              description={
                isEditMode
                  ? t('alertDestinations.configEditHint')
                  : t('alertDestinations.configCreateHint')
              }
              placeholder={CONFIG_PLACEHOLDERS[form.values.kind]}
              autosize
              minRows={4}
              styles={{ input: { fontFamily: 'monospace' } }}
              {...form.getInputProps('config')}
              disabled={saveMutation.isPending}
            />
            {error ? (
              <Alert color="red" variant="light">
                {error}
              </Alert>
            ) : null}
            <Group justify="flex-end">
              <Button variant="default" onClick={close} disabled={saveMutation.isPending}>
                {t('alertDestinations.cancel')}
              </Button>
              <Button type="submit" loading={saveMutation.isPending}>
                {isEditMode ? t('alertDestinations.save') : t('alertDestinations.create')}
              </Button>
            </Group>
          </Stack>
        </form>
      </Modal>
    </Stack>
  );
}
