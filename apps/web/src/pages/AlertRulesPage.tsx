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
  MultiSelect,
  NumberInput,
  Stack,
  Switch,
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
import { Link, useParams } from 'react-router';

import { type AlertRule, createAlertRule, deleteAlertRule, updateAlertRule } from '../api/alerts';
import { ApiError } from '../api/client';
import {
  useAlertDestinations,
  useAlertRules,
  useMyOrgs,
  useProjects,
  queryKeys,
} from '../api/queries';
import { useReportSoftError } from '../lib/reportSoftError';

const DEFAULT_CONDITIONS = '{\n  "level": { "in": ["error", "fatal"] }\n}';

interface DraftValues {
  name: string;
  conditions: string;
  destinationIds: string[];
  throttleSeconds: number;
  enabled: boolean;
}

const EMPTY_DRAFT: DraftValues = {
  name: '',
  conditions: DEFAULT_CONDITIONS,
  destinationIds: [],
  throttleSeconds: 300,
  enabled: true,
};

function ruleToDraft(rule: AlertRule): DraftValues {
  const ids = readDestinationIds(rule.actions);
  return {
    name: rule.name,
    conditions: JSON.stringify(rule.conditions ?? {}, null, 2),
    destinationIds: ids.map(String),
    throttleSeconds: rule.throttleSeconds,
    enabled: rule.enabled,
  };
}

function readDestinationIds(actions: unknown): number[] {
  if (!actions || typeof actions !== 'object') return [];
  const ids = (actions as { destinationIds?: unknown }).destinationIds;
  if (!Array.isArray(ids)) return [];
  return ids.filter((x): x is number => typeof x === 'number');
}

export function AlertRulesPage() {
  const { orgSlug, projectId: projectIdParam } = useParams();
  const projectId = projectIdParam ? Number(projectIdParam) : Number.NaN;
  const { t } = useTranslation();
  const queryClient = useQueryClient();

  const orgsQuery = useMyOrgs();
  const org = orgsQuery.data?.find((o) => o.slug === orgSlug);
  const projectsQuery = useProjects(org?.id);
  const project = projectsQuery.data?.find((p) => p.id === projectId);
  const rulesQuery = useAlertRules(Number.isFinite(projectId) ? projectId : undefined);
  const destinationsQuery = useAlertDestinations(org?.id);

  useReportSoftError(
    !Number.isFinite(projectId),
    `AlertRulesPage: invalid projectId param "${projectIdParam}"`,
  );
  useReportSoftError(
    Boolean(orgsQuery.data && !org && orgSlug),
    `AlertRulesPage: org slug "${orgSlug}" not in user's memberships`,
  );
  useReportSoftError(
    Boolean(org && projectsQuery.data && Number.isFinite(projectId) && !project),
    `AlertRulesPage: project ${projectId} not in org "${orgSlug}"`,
  );

  const [editing, setEditing] = useState<AlertRule | 'new' | null>(null);
  const [error, setError] = useState<string | null>(null);

  const form = useForm<DraftValues>({
    initialValues: EMPTY_DRAFT,
    validate: {
      name: (v) => (v.trim().length < 1 ? t('alertRules.errorName') : null),
      conditions: (v) => {
        if (!v.trim()) return t('alertRules.errorConditionsRequired');
        try {
          const parsed = JSON.parse(v);
          if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
            return t('alertRules.errorConditionsShape');
          }
          return null;
        } catch {
          return t('alertRules.errorConditionsJson');
        }
      },
      throttleSeconds: (v) => (v < 0 ? t('alertRules.errorThrottle') : null),
    },
  });

  const isEditMode = editing && editing !== 'new';

  function openCreate() {
    setError(null);
    form.setValues(EMPTY_DRAFT);
    setEditing('new');
  }

  function openEdit(rule: AlertRule) {
    setError(null);
    form.setValues(ruleToDraft(rule));
    setEditing(rule);
  }

  function close() {
    setEditing(null);
    form.reset();
    setError(null);
  }

  const saveMutation = useMutation({
    mutationFn: async (values: DraftValues) => {
      if (!Number.isFinite(projectId)) throw new Error('project id missing');
      const conditions = JSON.parse(values.conditions) as unknown;
      const actions = { destinationIds: values.destinationIds.map(Number) };
      const body = {
        name: values.name,
        conditions,
        actions,
        throttleSeconds: values.throttleSeconds,
        enabled: values.enabled,
      };
      if (isEditMode) return updateAlertRule(projectId, editing.id, body);
      return createAlertRule(projectId, body);
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: queryKeys.alertRules(projectId) });
      close();
    },
    onError: (err: unknown) => {
      setError(err instanceof ApiError ? (err.problem.detail ?? err.problem.title) : String(err));
    },
  });

  const deleteMutation = useMutation({
    mutationFn: async (id: number) => {
      if (!Number.isFinite(projectId)) throw new Error('project id missing');
      return deleteAlertRule(projectId, id);
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: queryKeys.alertRules(projectId) });
    },
  });

  if (!Number.isFinite(projectId)) {
    return (
      <Stack>
        <Title order={3}>{t('alertRules.title')}</Title>
        <Text c="dimmed">{t('issues.invalidProjectId')}</Text>
      </Stack>
    );
  }

  if (orgsQuery.isLoading) {
    return (
      <Center mih={200}>
        <Loader />
      </Center>
    );
  }

  const destinationOptions =
    destinationsQuery.data?.map((d) => ({ value: String(d.id), label: `${d.name} (${d.kind})` })) ??
    [];

  const noDestinations = destinationsQuery.isSuccess && destinationOptions.length === 0;

  return (
    <Stack>
      <Group justify="space-between">
        <div>
          <Title order={3}>{t('alertRules.title')}</Title>
          {project ? (
            <Text size="sm" c="dimmed">
              {project.name}
            </Text>
          ) : null}
        </div>
        <Button leftSection={<IconPlus size={16} />} onClick={openCreate}>
          {t('alertRules.new')}
        </Button>
      </Group>

      {noDestinations ? (
        <Alert color="yellow" variant="light">
          {t('alertRules.needDestinations')}{' '}
          <Text component={Link} to={`/orgs/${orgSlug}/destinations`} fw={500}>
            {t('alertRules.goToDestinations')}
          </Text>
        </Alert>
      ) : null}

      {rulesQuery.isLoading ? (
        <Center mih={120}>
          <Loader size="sm" />
        </Center>
      ) : rulesQuery.data && rulesQuery.data.length === 0 ? (
        <Card withBorder padding="lg">
          <Text c="dimmed">{t('alertRules.empty')}</Text>
        </Card>
      ) : (
        <Card withBorder padding={0}>
          <Table data-testid="alert-rules-table">
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('alertRules.colName')}</Table.Th>
                <Table.Th>{t('alertRules.colDestinations')}</Table.Th>
                <Table.Th>{t('alertRules.colThrottle')}</Table.Th>
                <Table.Th>{t('alertRules.colEnabled')}</Table.Th>
                <Table.Th style={{ width: 110, textAlign: 'right' }}>
                  {t('alertRules.colActions')}
                </Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {rulesQuery.data?.map((rule) => {
                const ids = readDestinationIds(rule.actions);
                return (
                  <Table.Tr key={rule.id}>
                    <Table.Td>{rule.name}</Table.Td>
                    <Table.Td>
                      <Group gap={4}>
                        {ids.length === 0 ? (
                          <Text size="xs" c="dimmed">
                            —
                          </Text>
                        ) : (
                          ids.map((id) => {
                            const d = destinationsQuery.data?.find((x) => x.id === id);
                            return (
                              <Badge key={id} variant="light">
                                {d ? `${d.name} (${d.kind})` : `#${id}`}
                              </Badge>
                            );
                          })
                        )}
                      </Group>
                    </Table.Td>
                    <Table.Td>{rule.throttleSeconds}s</Table.Td>
                    <Table.Td>
                      {rule.enabled ? (
                        <Badge color="green" variant="light">
                          {t('alertRules.statusOn')}
                        </Badge>
                      ) : (
                        <Badge color="gray" variant="light">
                          {t('alertRules.statusOff')}
                        </Badge>
                      )}
                    </Table.Td>
                    <Table.Td style={{ textAlign: 'right' }}>
                      <Group gap="xs" justify="flex-end">
                        <ActionIcon
                          variant="subtle"
                          aria-label={t('alertRules.editAria', { name: rule.name })}
                          onClick={() => openEdit(rule)}
                        >
                          <IconPencil size={16} />
                        </ActionIcon>
                        <ActionIcon
                          variant="subtle"
                          color="red"
                          aria-label={t('alertRules.deleteAria', { name: rule.name })}
                          onClick={() => deleteMutation.mutate(rule.id)}
                          loading={deleteMutation.isPending && deleteMutation.variables === rule.id}
                        >
                          <IconTrash size={16} />
                        </ActionIcon>
                      </Group>
                    </Table.Td>
                  </Table.Tr>
                );
              })}
            </Table.Tbody>
          </Table>
        </Card>
      )}

      <Modal
        opened={editing !== null}
        onClose={close}
        title={isEditMode ? t('alertRules.editTitle') : t('alertRules.createTitle')}
        size="lg"
      >
        <form onSubmit={form.onSubmit((values) => saveMutation.mutate(values))}>
          <Stack>
            <TextInput
              label={t('alertRules.name')}
              {...form.getInputProps('name')}
              disabled={saveMutation.isPending}
            />
            <Textarea
              label={t('alertRules.conditions')}
              description={t('alertRules.conditionsHint')}
              autosize
              minRows={5}
              styles={{ input: { fontFamily: 'monospace' } }}
              {...form.getInputProps('conditions')}
              disabled={saveMutation.isPending}
            />
            <MultiSelect
              label={t('alertRules.destinations')}
              data={destinationOptions}
              value={form.values.destinationIds}
              onChange={(value) => form.setFieldValue('destinationIds', value)}
              searchable
              disabled={saveMutation.isPending}
              nothingFoundMessage={t('alertRules.noDestinationsFound')}
            />
            <NumberInput
              label={t('alertRules.throttle')}
              description={t('alertRules.throttleHint')}
              min={0}
              {...form.getInputProps('throttleSeconds')}
              disabled={saveMutation.isPending}
            />
            <Switch
              label={t('alertRules.enabledLabel')}
              checked={form.values.enabled}
              onChange={(event) => form.setFieldValue('enabled', event.currentTarget.checked)}
              disabled={saveMutation.isPending}
            />
            {error ? (
              <Alert color="red" variant="light">
                {error}
              </Alert>
            ) : null}
            <Group justify="flex-end">
              <Button variant="default" onClick={close} disabled={saveMutation.isPending}>
                {t('alertRules.cancel')}
              </Button>
              <Button type="submit" loading={saveMutation.isPending}>
                {isEditMode ? t('alertRules.save') : t('alertRules.create')}
              </Button>
            </Group>
          </Stack>
        </form>
      </Modal>
    </Stack>
  );
}
