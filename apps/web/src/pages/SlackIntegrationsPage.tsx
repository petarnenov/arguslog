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
  Title,
} from '@mantine/core';
import { IconBellPlus, IconBrandSlack, IconCheck, IconTrash } from '@tabler/icons-react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useParams, useSearchParams } from 'react-router';

import { ApiError } from '../api/client';
import { queryKeys, useMyOrgs, useProjects, useSlackWorkspaces } from '../api/queries';
import {
  createSlackAlertDestination,
  deleteSlackWorkspace,
  setSlackDefaultProject,
  startSlackInstall,
  type SlackWorkspace,
} from '../api/slackIntegrations';
import { useReportSoftError } from '../lib/reportSoftError';

export function SlackIntegrationsPage() {
  const { orgSlug } = useParams();
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [searchParams, setSearchParams] = useSearchParams();

  const orgsQuery = useMyOrgs();
  const org = orgsQuery.data?.find((o) => o.slug === orgSlug);
  const workspacesQuery = useSlackWorkspaces(org?.id);
  const projectsQuery = useProjects(org?.id);

  const [pendingDelete, setPendingDelete] = useState<SlackWorkspace | null>(null);
  const [banner, setBanner] = useState<{ kind: 'success' | 'error'; message: string } | null>(null);

  useReportSoftError(
    Boolean(orgsQuery.data && !org && orgSlug),
    `SlackIntegrationsPage: org slug "${orgSlug}" not in user's memberships`,
  );

  // The OAuth callback redirects back here with ?installed=<team-name> or ?error=<code>. Drain
  // those query params on mount so a refresh doesn't keep firing the banner forever.
  useEffect(() => {
    const installed = searchParams.get('installed');
    const error = searchParams.get('error');
    if (installed) {
      setBanner({
        kind: 'success',
        message: t('slackIntegrations.connected', { team: installed }),
      });
      const next = new URLSearchParams(searchParams);
      next.delete('installed');
      setSearchParams(next, { replace: true });
    } else if (error) {
      setBanner({ kind: 'error', message: t('slackIntegrations.connectError', { error }) });
      const next = new URLSearchParams(searchParams);
      next.delete('error');
      setSearchParams(next, { replace: true });
    }
    // Intentionally only on mount; the dependency-list-includes-everything lint would loop us.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const startInstallMutation = useMutation({
    mutationFn: async (orgId: number) => startSlackInstall(orgId),
    onSuccess: (res) => {
      window.location.assign(res.authorizeUrl);
    },
    onError: (err: unknown) => {
      setBanner({
        kind: 'error',
        message:
          err instanceof ApiError
            ? (err.problem.detail ?? err.problem.title ?? String(err))
            : String(err),
      });
    },
  });

  const setDefaultProjectMutation = useMutation({
    mutationFn: async (args: { id: number; defaultProjectId: number | null }) => {
      if (!org) throw new Error('org missing');
      return setSlackDefaultProject(org.id, args.id, args.defaultProjectId);
    },
    onSuccess: async () => {
      if (org) {
        await queryClient.invalidateQueries({ queryKey: queryKeys.slackWorkspaces(org.id) });
      }
    },
    onError: (err: unknown) => {
      setBanner({
        kind: 'error',
        message:
          err instanceof ApiError
            ? (err.problem.detail ?? err.problem.title ?? String(err))
            : String(err),
      });
    },
  });

  const createDestinationMutation = useMutation({
    mutationFn: async (workspaceId: number) => {
      if (!org) throw new Error('org missing');
      return createSlackAlertDestination(org.id, workspaceId);
    },
    onSuccess: (dest) => {
      setBanner({
        kind: 'success',
        message: t('slackIntegrations.alertDestinationCreated', { name: dest.name }),
      });
    },
    onError: (err: unknown) => {
      setBanner({
        kind: 'error',
        message:
          err instanceof ApiError
            ? (err.problem.detail ?? err.problem.title ?? String(err))
            : String(err),
      });
    },
  });

  const deleteMutation = useMutation({
    mutationFn: async (id: number) => {
      if (!org) throw new Error('org missing');
      return deleteSlackWorkspace(org.id, id);
    },
    onSuccess: async () => {
      if (org) {
        await queryClient.invalidateQueries({ queryKey: queryKeys.slackWorkspaces(org.id) });
      }
      setPendingDelete(null);
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
        <Title order={3}>{t('slackIntegrations.title')}</Title>
        <Text c="dimmed">{t('projects.orgNotFound')}</Text>
      </Stack>
    );
  }

  const projectOptions = (projectsQuery.data ?? []).map((p) => ({
    value: String(p.id),
    label: p.name,
  }));

  return (
    <Stack>
      <Group justify="space-between">
        <Title order={3}>{t('slackIntegrations.title')}</Title>
        <Button
          onClick={() => startInstallMutation.mutate(org.id)}
          loading={startInstallMutation.isPending}
          leftSection={<IconBrandSlack size={16} />}
          data-testid="slack-connect-button"
        >
          {t('slackIntegrations.connect')}
        </Button>
      </Group>

      <Text c="dimmed" size="sm">
        {t('slackIntegrations.lead')}
      </Text>

      {banner && (
        <Alert
          color={banner.kind === 'success' ? 'green' : 'red'}
          icon={banner.kind === 'success' ? <IconCheck size={16} /> : undefined}
          withCloseButton
          onClose={() => setBanner(null)}
        >
          {banner.message}
        </Alert>
      )}

      {workspacesQuery.isLoading ? (
        <Center mih={120}>
          <Loader size="sm" />
        </Center>
      ) : workspacesQuery.data && workspacesQuery.data.length === 0 ? (
        <Card withBorder padding="lg">
          <Text c="dimmed">{t('slackIntegrations.empty')}</Text>
        </Card>
      ) : (
        <Card withBorder padding={0}>
          <Table data-testid="slack-workspaces-table">
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('slackIntegrations.colTeam')}</Table.Th>
                <Table.Th>{t('slackIntegrations.colDefaultProject')}</Table.Th>
                <Table.Th>{t('slackIntegrations.colStatus')}</Table.Th>
                <Table.Th style={{ width: 80, textAlign: 'right' }}>
                  {t('slackIntegrations.colActions')}
                </Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {(workspacesQuery.data ?? []).map((w) => (
                <Table.Tr key={w.id} data-testid={`slack-workspace-row-${w.id}`}>
                  <Table.Td>
                    <Stack gap={2}>
                      <Text fw={500}>{w.slackTeamName || w.slackTeamId}</Text>
                      <Text size="xs" c="dimmed">
                        {w.slackTeamId}
                      </Text>
                    </Stack>
                  </Table.Td>
                  <Table.Td>
                    {w.active ? (
                      <Select
                        data={projectOptions}
                        value={w.defaultProjectId == null ? null : String(w.defaultProjectId)}
                        onChange={(value) =>
                          setDefaultProjectMutation.mutate({
                            id: w.id,
                            defaultProjectId: value == null ? null : Number(value),
                          })
                        }
                        placeholder={t('slackIntegrations.pickProject')}
                        clearable
                        searchable
                        disabled={setDefaultProjectMutation.isPending}
                        data-testid={`slack-default-project-${w.id}`}
                      />
                    ) : (
                      <Text size="sm" c="dimmed">
                        —
                      </Text>
                    )}
                  </Table.Td>
                  <Table.Td>
                    {w.active ? (
                      <Badge color="green" variant="light">
                        {t('slackIntegrations.active')}
                      </Badge>
                    ) : (
                      <Badge color="gray" variant="light">
                        {t('slackIntegrations.revoked')}
                      </Badge>
                    )}
                  </Table.Td>
                  <Table.Td style={{ textAlign: 'right' }}>
                    {w.active && (
                      <Group gap="xs" justify="flex-end" wrap="nowrap">
                        {w.hasWebhook && (
                          <ActionIcon
                            variant="subtle"
                            color="blue"
                            aria-label={t('slackIntegrations.createAlertDestination')}
                            title={t('slackIntegrations.createAlertDestinationTip', {
                              channel: w.webhookChannel ?? 'Slack',
                            })}
                            data-testid={`slack-create-destination-${w.id}`}
                            loading={
                              createDestinationMutation.isPending &&
                              createDestinationMutation.variables === w.id
                            }
                            onClick={() => createDestinationMutation.mutate(w.id)}
                          >
                            <IconBellPlus size={16} />
                          </ActionIcon>
                        )}
                        <ActionIcon
                          variant="subtle"
                          color="red"
                          aria-label={t('slackIntegrations.disconnect')}
                          data-testid={`slack-disconnect-${w.id}`}
                          onClick={() => setPendingDelete(w)}
                        >
                          <IconTrash size={16} />
                        </ActionIcon>
                      </Group>
                    )}
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Card>
      )}

      <Modal
        opened={pendingDelete != null}
        onClose={() => {
          if (!deleteMutation.isPending) setPendingDelete(null);
        }}
        title={t('slackIntegrations.disconnectTitle')}
      >
        <Stack>
          <Text size="sm">
            {t('slackIntegrations.disconnectBody', {
              team: pendingDelete?.slackTeamName || pendingDelete?.slackTeamId,
            })}
          </Text>
          <Group justify="flex-end">
            <Button variant="default" onClick={() => setPendingDelete(null)}>
              {t('common.cancel')}
            </Button>
            <Button
              color="red"
              loading={deleteMutation.isPending}
              onClick={() => pendingDelete && deleteMutation.mutate(pendingDelete.id)}
              data-testid="slack-disconnect-confirm"
            >
              {t('slackIntegrations.disconnect')}
            </Button>
          </Group>
        </Stack>
      </Modal>
    </Stack>
  );
}
