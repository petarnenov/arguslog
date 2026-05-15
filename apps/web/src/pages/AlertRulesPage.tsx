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
  Select,
  Stack,
  Switch,
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
import { Link, useParams } from 'react-router';

import {
  type AlertLevel,
  type AlertRule,
  type AlertRuleConditions,
  createAlertRule,
  deleteAlertRule,
  updateAlertRule,
} from '../api/alerts';
import { ApiError } from '../api/client';
import {
  useAlertDestinations,
  useAlertRules,
  useMyOrgs,
  useProjects,
  queryKeys,
} from '../api/queries';
import { useReportSoftError } from '../lib/reportSoftError';

const LEVELS: AlertLevel[] = ['fatal', 'error', 'warning', 'info', 'debug'];
type WindowUnit = 'minutes' | 'hours' | 'days';

interface DraftValues {
  name: string;
  levels: AlertLevel[];
  windowValue: number | '';
  windowUnit: WindowUnit;
  windowAdvanced: string; // populated when an existing rule's duration doesn't fit value+unit
  occurrenceThreshold: number | '';
  tagKey: string;
  tagValues: string[];
  destinationIds: string[];
  throttleSeconds: number;
  enabled: boolean;
}

const EMPTY_DRAFT: DraftValues = {
  name: '',
  levels: ['error', 'fatal'],
  windowValue: '',
  windowUnit: 'minutes',
  windowAdvanced: '',
  occurrenceThreshold: '',
  tagKey: '',
  tagValues: [],
  destinationIds: [],
  throttleSeconds: 300,
  enabled: true,
};

const SIMPLE_DURATION = /^(?:PT(\d+)(M|H)|P(\d+)D)$/;

function toIso(value: number, unit: WindowUnit): string {
  switch (unit) {
    case 'minutes':
      return `PT${value}M`;
    case 'hours':
      return `PT${value}H`;
    case 'days':
      return `P${value}D`;
  }
}

function parseIso(
  iso: string | undefined,
): { value: number; unit: WindowUnit } | { advanced: string } | null {
  if (!iso) return null;
  const m = iso.match(SIMPLE_DURATION);
  if (!m) return { advanced: iso };
  if (m[3]) return { value: Number(m[3]), unit: 'days' };
  const value = Number(m[1]);
  const unit: WindowUnit = m[2] === 'H' ? 'hours' : 'minutes';
  return { value, unit };
}

function ruleToDraft(rule: AlertRule): DraftValues {
  const c = rule.conditions ?? {};
  const parsed = parseIso(c.firstSeenWindow);
  return {
    name: rule.name,
    levels: c.level?.in ?? [],
    windowValue: parsed && 'value' in parsed ? parsed.value : '',
    windowUnit: parsed && 'unit' in parsed ? parsed.unit : 'minutes',
    windowAdvanced: parsed && 'advanced' in parsed ? parsed.advanced : '',
    occurrenceThreshold: c.occurrenceThreshold ?? '',
    tagKey: c.tag?.key ?? '',
    tagValues: c.tag?.in ?? [],
    destinationIds: (rule.actions?.destinationIds ?? []).map(String),
    throttleSeconds: rule.throttleSeconds,
    enabled: rule.enabled,
  };
}

function buildConditions(values: DraftValues): {
  conditions: AlertRuleConditions;
  error: string | null;
} {
  const conditions: AlertRuleConditions = {};
  if (values.levels.length > 0) conditions.level = { in: values.levels };
  if (values.windowAdvanced.trim()) {
    conditions.firstSeenWindow = values.windowAdvanced.trim();
  } else if (typeof values.windowValue === 'number' && values.windowValue > 0) {
    conditions.firstSeenWindow = toIso(values.windowValue, values.windowUnit);
  }
  if (typeof values.occurrenceThreshold === 'number' && values.occurrenceThreshold >= 1) {
    conditions.occurrenceThreshold = values.occurrenceThreshold;
  }
  const tagKey = values.tagKey.trim();
  const tagValues = values.tagValues.map((v) => v.trim()).filter((v) => v.length > 0);
  if (tagKey && tagValues.length > 0) {
    conditions.tag = { key: tagKey, in: tagValues };
  } else if (tagKey || tagValues.length > 0) {
    return { conditions, error: 'Both Tag key and Tag values are required when filtering by tag.' };
  }
  return { conditions, error: null };
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
      const built = buildConditions(values);
      if (built.error) throw new Error(built.error);
      const body = {
        name: values.name,
        conditions: built.conditions,
        actions: { destinationIds: values.destinationIds.map(Number) },
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
                const ids = rule.actions?.destinationIds ?? [];
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

            <MultiSelect
              label={t('alertRules.level')}
              description={t('alertRules.levelHint')}
              data={LEVELS.map((l) => ({ value: l, label: l }))}
              value={form.values.levels}
              onChange={(v) => form.setFieldValue('levels', v as AlertLevel[])}
              disabled={saveMutation.isPending}
              placeholder={t('alertRules.levelAny')}
            />

            {form.values.windowAdvanced ? (
              <TextInput
                label={t('alertRules.windowAdvanced')}
                description={t('alertRules.windowAdvancedHint')}
                {...form.getInputProps('windowAdvanced')}
                disabled={saveMutation.isPending}
                styles={{ input: { fontFamily: 'monospace' } }}
              />
            ) : (
              <Group grow align="end">
                <NumberInput
                  label={t('alertRules.window')}
                  description={t('alertRules.windowHint')}
                  min={1}
                  allowDecimal={false}
                  placeholder={t('alertRules.windowAny')}
                  {...form.getInputProps('windowValue')}
                  disabled={saveMutation.isPending}
                />
                <Select
                  label={t('alertRules.windowUnit')}
                  data={[
                    { value: 'minutes', label: t('alertRules.windowUnits.minutes') },
                    { value: 'hours', label: t('alertRules.windowUnits.hours') },
                    { value: 'days', label: t('alertRules.windowUnits.days') },
                  ]}
                  value={form.values.windowUnit}
                  onChange={(v) =>
                    form.setFieldValue('windowUnit', (v ?? 'minutes') as WindowUnit)
                  }
                  disabled={saveMutation.isPending}
                  allowDeselect={false}
                />
              </Group>
            )}

            <NumberInput
              label={t('alertRules.threshold')}
              description={t('alertRules.thresholdHint')}
              min={1}
              allowDecimal={false}
              placeholder="1"
              {...form.getInputProps('occurrenceThreshold')}
              disabled={saveMutation.isPending}
            />

            <Group grow align="start">
              <TextInput
                label={t('alertRules.tagKey')}
                description={t('alertRules.tagKeyHint')}
                placeholder="env"
                {...form.getInputProps('tagKey')}
                disabled={saveMutation.isPending}
              />
              <TagsInput
                label={t('alertRules.tagValues')}
                description={t('alertRules.tagValuesHint')}
                placeholder={t('alertRules.tagValuesPlaceholder')}
                value={form.values.tagValues}
                onChange={(v) => form.setFieldValue('tagValues', v)}
                splitChars={[',', ' ', ';']}
                disabled={saveMutation.isPending || !form.values.tagKey.trim()}
              />
            </Group>

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
