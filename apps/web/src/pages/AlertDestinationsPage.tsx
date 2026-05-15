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
  PasswordInput,
  Select,
  Stack,
  Table,
  TagsInput,
  Text,
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
import { useReportSoftError } from '../lib/reportSoftError';

const KIND_OPTIONS: { value: DestinationKind; label: string }[] = [
  { value: 'telegram', label: 'Telegram' },
  { value: 'email', label: 'Email' },
  { value: 'slack', label: 'Slack' },
  { value: 'webhook', label: 'Webhook' },
];

interface DraftValues {
  kind: DestinationKind;
  name: string;
  // Per-kind config fields. Each kind reads/writes its own subset.
  // Telegram
  chatId: string;
  botToken: string;
  // Email
  emailTo: string[];
  // Slack
  slackWebhookUrl: string;
  // Webhook (generic)
  webhookUrl: string;
  webhookSecret: string;
}

const EMPTY_DRAFT: DraftValues = {
  kind: 'telegram',
  name: '',
  chatId: '',
  botToken: '',
  emailTo: [],
  slackWebhookUrl: '',
  webhookUrl: '',
  webhookSecret: '',
};

/**
 * Builds the JSON config payload from form state for the given kind. Returns null when the
 * caller's editing an existing destination and left every config field blank — that's the
 * "leave config alone, just rename" path the api supports.
 */
function buildConfigPayload(
  values: DraftValues,
  isEditMode: boolean,
): { config: Record<string, unknown> | null; error: string | null } {
  switch (values.kind) {
    case 'telegram': {
      const chatId = values.chatId.trim();
      const botToken = values.botToken.trim();
      if (isEditMode && !chatId && !botToken) return { config: null, error: null };
      if (!chatId || !botToken) {
        return {
          config: null,
          error: 'Telegram needs both Chat ID and Bot token (or leave both blank to keep current).',
        };
      }
      return { config: { chatId, botToken }, error: null };
    }
    case 'email': {
      const recipients = values.emailTo.map((s) => s.trim()).filter((s) => s.length > 0);
      if (isEditMode && recipients.length === 0) return { config: null, error: null };
      if (recipients.length === 0) {
        return { config: null, error: 'Add at least one recipient email.' };
      }
      return { config: { to: recipients }, error: null };
    }
    case 'slack': {
      const webhookUrl = values.slackWebhookUrl.trim();
      if (isEditMode && !webhookUrl) return { config: null, error: null };
      if (!webhookUrl) return { config: null, error: 'Slack webhook URL is required.' };
      return { config: { webhookUrl }, error: null };
    }
    case 'webhook': {
      const url = values.webhookUrl.trim();
      const secret = values.webhookSecret.trim();
      if (isEditMode && !url && !secret) return { config: null, error: null };
      if (!url) return { config: null, error: 'Webhook URL is required.' };
      const config: Record<string, unknown> = { url };
      if (secret) config.secret = secret;
      return { config, error: null };
    }
  }
}

export function AlertDestinationsPage() {
  const { orgSlug } = useParams();
  const { t } = useTranslation();
  const queryClient = useQueryClient();

  const orgsQuery = useMyOrgs();
  const org = orgsQuery.data?.find((o) => o.slug === orgSlug);
  const destinationsQuery = useAlertDestinations(org?.id);

  useReportSoftError(
    Boolean(orgsQuery.data && !org && orgSlug),
    `AlertDestinationsPage: org slug "${orgSlug}" not in user's memberships`,
  );

  const [editing, setEditing] = useState<AlertDestination | 'new' | null>(null);
  const [error, setError] = useState<string | null>(null);

  const form = useForm<DraftValues>({
    initialValues: EMPTY_DRAFT,
    validate: {
      name: (v) => (v.trim().length < 1 ? t('alertDestinations.errorName') : null),
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
    // Config field stays blank on edit — the api never echoes the secret back, and the form
    // treats "all config inputs blank" as "leave the existing encrypted blob alone, just
    // rename." If the user wants to rotate a secret, they fill the relevant input and submit.
    form.setValues({ ...EMPTY_DRAFT, kind: d.kind, name: d.name });
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
      const built = buildConfigPayload(values, Boolean(isEditMode));
      if (built.error) {
        throw new Error(built.error);
      }
      if (isEditMode) {
        return updateAlertDestination(org.id, editing.id, {
          kind: values.kind,
          name: values.name,
          config: built.config,
        });
      }
      // Create path: TypeScript types config as required, but the buildConfigPayload guarantees
      // a non-null object here (the isEditMode=false branch rejects blank-everywhere above).
      return createAlertDestination(org.id, {
        kind: values.kind,
        name: values.name,
        config: built.config as Record<string, unknown>,
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

            {form.values.kind === 'telegram' && (
              <>
                <TextInput
                  label={t('alertDestinations.telegramChatId')}
                  description={t('alertDestinations.telegramChatIdHint')}
                  placeholder="-1001234567890"
                  {...form.getInputProps('chatId')}
                  disabled={saveMutation.isPending}
                />
                <PasswordInput
                  label={t('alertDestinations.telegramBotToken')}
                  description={
                    isEditMode
                      ? t('alertDestinations.secretEditHint')
                      : t('alertDestinations.telegramBotTokenHint')
                  }
                  placeholder={isEditMode ? '••••••••' : '12345:ABC-DEF...'}
                  {...form.getInputProps('botToken')}
                  disabled={saveMutation.isPending}
                />
              </>
            )}

            {form.values.kind === 'email' && (
              <TagsInput
                label={t('alertDestinations.emailTo')}
                description={t('alertDestinations.emailToHint')}
                placeholder={isEditMode ? t('alertDestinations.leaveBlankToKeep') : 'ops@example.com'}
                value={form.values.emailTo}
                onChange={(v) => form.setFieldValue('emailTo', v)}
                splitChars={[',', ' ', ';']}
                disabled={saveMutation.isPending}
              />
            )}

            {form.values.kind === 'slack' && (
              <PasswordInput
                label={t('alertDestinations.slackWebhookUrl')}
                description={
                  isEditMode
                    ? t('alertDestinations.secretEditHint')
                    : t('alertDestinations.slackWebhookUrlHint')
                }
                placeholder={isEditMode ? '••••••••' : 'https://hooks.slack.com/services/T00/B00/XXX'}
                {...form.getInputProps('slackWebhookUrl')}
                disabled={saveMutation.isPending}
              />
            )}

            {form.values.kind === 'webhook' && (
              <>
                <TextInput
                  label={t('alertDestinations.webhookUrl')}
                  description={
                    isEditMode
                      ? t('alertDestinations.leaveBlankToKeep')
                      : t('alertDestinations.webhookUrlHint')
                  }
                  placeholder="https://hooks.example.com/arguslog"
                  {...form.getInputProps('webhookUrl')}
                  disabled={saveMutation.isPending}
                />
                <PasswordInput
                  label={t('alertDestinations.webhookSecret')}
                  description={
                    isEditMode
                      ? t('alertDestinations.secretEditHint')
                      : t('alertDestinations.webhookSecretHint')
                  }
                  placeholder={isEditMode ? '••••••••' : t('alertDestinations.webhookSecretPlaceholder')}
                  {...form.getInputProps('webhookSecret')}
                  disabled={saveMutation.isPending}
                />
              </>
            )}
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
