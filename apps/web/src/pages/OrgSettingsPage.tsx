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
  TextInput,
  Title,
} from '@mantine/core';
import { useForm } from '@mantine/form';
import { IconLogout, IconPlus, IconTrash } from '@tabler/icons-react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate, useParams } from 'react-router';

import { ApiError } from '../api/client';
import {
  changeOrgMemberRole,
  inviteOrgMember,
  type MemberRole,
  type OrgMember,
  removeOrgMember,
} from '../api/members';
import { queryKeys, useMyOrgs, useOrgMembers } from '../api/queries';
import { useAuthStore } from '../auth/useAuthStore';
import { useReportSoftError } from '../lib/reportSoftError';

const ROLE_OPTIONS: { value: MemberRole; labelKey: string }[] = [
  { value: 'owner', labelKey: 'members.roleOwner' },
  { value: 'admin', labelKey: 'members.roleAdmin' },
  { value: 'member', labelKey: 'members.roleMember' },
];

interface InviteValues {
  email: string;
  role: MemberRole;
}

export function OrgSettingsPage() {
  const { orgSlug } = useParams();
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const currentUserId = useAuthStore((s) => s.user?.id);

  const orgsQuery = useMyOrgs();
  const org = orgsQuery.data?.find((o) => o.slug === orgSlug);
  const membersQuery = useOrgMembers(org?.id);

  useReportSoftError(
    Boolean(orgsQuery.data && !org && orgSlug),
    `OrgSettingsPage: org slug "${orgSlug}" not in user's memberships`,
  );

  const myMembership = membersQuery.data?.find((m) => m.userId === currentUserId);
  const isOwner = myMembership?.role === 'owner';

  const [inviteOpen, setInviteOpen] = useState(false);
  const [confirmRemove, setConfirmRemove] = useState<OrgMember | null>(null);
  const [confirmLeave, setConfirmLeave] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const inviteForm = useForm<InviteValues>({
    initialValues: { email: '', role: 'member' },
    validate: {
      email: (v) => {
        const trimmed = v.trim();
        if (!trimmed) return t('members.errorEmail');
        const at = trimmed.indexOf('@');
        if (at <= 0 || at === trimmed.length - 1 || trimmed.indexOf('@', at + 1) >= 0) {
          return t('members.errorEmail');
        }
        return null;
      },
    },
  });

  const inviteMutation = useMutation({
    mutationFn: async (values: InviteValues) => {
      if (!org) throw new Error('org missing');
      return inviteOrgMember(org.id, { email: values.email.trim(), role: values.role });
    },
    onSuccess: async () => {
      if (org) {
        await queryClient.invalidateQueries({ queryKey: queryKeys.orgMembers(org.id) });
      }
      setInviteOpen(false);
      inviteForm.reset();
      setError(null);
    },
    onError: (err: unknown) => {
      setError(err instanceof ApiError ? (err.problem.detail ?? err.problem.title) : String(err));
    },
  });

  const roleMutation = useMutation({
    mutationFn: async (input: { userId: string; role: MemberRole }) => {
      if (!org) throw new Error('org missing');
      return changeOrgMemberRole(org.id, input.userId, input.role);
    },
    onSuccess: async () => {
      if (org) {
        await queryClient.invalidateQueries({ queryKey: queryKeys.orgMembers(org.id) });
      }
    },
  });

  const removeMutation = useMutation({
    mutationFn: async (userId: string) => {
      if (!org) throw new Error('org missing');
      return removeOrgMember(org.id, userId);
    },
    onSuccess: async (_, userId) => {
      if (org) {
        await queryClient.invalidateQueries({ queryKey: queryKeys.orgMembers(org.id) });
      }
      // If the user removed themselves, kick them back to the org list.
      if (userId === currentUserId) {
        await queryClient.invalidateQueries({ queryKey: queryKeys.myOrgs() });
        navigate('/orgs');
      }
      setConfirmRemove(null);
      setConfirmLeave(false);
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
        <Title order={3}>{t('members.title')}</Title>
        <Text c="dimmed">{t('projects.orgNotFound')}</Text>
      </Stack>
    );
  }

  return (
    <Stack>
      <Group justify="space-between">
        <Title order={3}>{t('members.title')}</Title>
        {isOwner ? (
          <Button leftSection={<IconPlus size={16} />} onClick={() => setInviteOpen(true)}>
            {t('members.invite')}
          </Button>
        ) : null}
      </Group>

      <Text c="dimmed" size="sm">
        {t('members.intro')}
      </Text>

      {membersQuery.isLoading ? (
        <Center mih={120}>
          <Loader size="sm" />
        </Center>
      ) : membersQuery.data && membersQuery.data.length === 0 ? (
        <Card withBorder padding="lg">
          <Text c="dimmed">{t('members.empty')}</Text>
        </Card>
      ) : (
        <Card withBorder padding={0}>
          <Table data-testid="members-table">
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('members.colName')}</Table.Th>
                <Table.Th style={{ width: 200 }}>{t('members.colRole')}</Table.Th>
                <Table.Th style={{ width: 160 }}>{t('members.colAdded')}</Table.Th>
                <Table.Th style={{ width: 110, textAlign: 'right' }}>
                  {t('members.colActions')}
                </Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {membersQuery.data?.map((m) => {
                const isSelf = m.userId === currentUserId;
                const canChangeRole = isOwner && !isSelf;
                const canRemove = isOwner || isSelf;
                // displayName goes null only for placeholder rows; fall back to the email's
                // local-part so the row never reads as an opaque "(invitation pending)" label.
                // The explicit Pending badge below carries that state — keeping the two signals
                // separate means a freshly-signed-in invitee whose displayName backfilled from
                // local-part already looks like a real member while the badge fades on next poll.
                const displayName = m.displayName ?? m.email.split('@')[0];
                return (
                  <Table.Tr key={m.userId}>
                    <Table.Td>
                      <Stack gap={0}>
                        <Group gap="xs" wrap="nowrap">
                          <Text size="sm" fw={500}>
                            {displayName}
                            {isSelf ? (
                              <Text span c="dimmed">
                                {' ' + t('members.youSuffix')}
                              </Text>
                            ) : null}
                          </Text>
                          {m.pending ? (
                            <Badge
                              color="yellow"
                              variant="light"
                              size="sm"
                              title={t('members.pendingTooltip')}
                            >
                              {t('members.pendingBadge')}
                            </Badge>
                          ) : null}
                        </Group>
                        <Text size="xs" c="dimmed">
                          {m.email}
                        </Text>
                      </Stack>
                    </Table.Td>
                    <Table.Td>
                      {canChangeRole ? (
                        <Select
                          data={ROLE_OPTIONS.map((opt) => ({
                            value: opt.value,
                            label: t(opt.labelKey),
                          }))}
                          value={m.role}
                          onChange={(value) => {
                            if (value && value !== m.role) {
                              roleMutation.mutate({ userId: m.userId, role: value as MemberRole });
                            }
                          }}
                          disabled={
                            roleMutation.isPending && roleMutation.variables?.userId === m.userId
                          }
                          aria-label={t('members.colRole')}
                        />
                      ) : (
                        <Badge variant="light">{t(`members.role${capitalize(m.role)}`)}</Badge>
                      )}
                    </Table.Td>
                    <Table.Td>
                      <Text size="sm" c="dimmed">
                        {new Date(m.addedAt).toLocaleDateString()}
                      </Text>
                    </Table.Td>
                    <Table.Td style={{ textAlign: 'right' }}>
                      {canRemove ? (
                        <ActionIcon
                          variant="subtle"
                          color="red"
                          aria-label={
                            isSelf
                              ? t('members.leave')
                              : t('members.removeAria', { email: m.email })
                          }
                          onClick={() => {
                            if (isSelf) {
                              setConfirmLeave(true);
                            } else {
                              setConfirmRemove(m);
                            }
                          }}
                        >
                          {isSelf ? <IconLogout size={16} /> : <IconTrash size={16} />}
                        </ActionIcon>
                      ) : null}
                    </Table.Td>
                  </Table.Tr>
                );
              })}
            </Table.Tbody>
          </Table>
        </Card>
      )}

      <Modal
        opened={inviteOpen}
        onClose={() => {
          setInviteOpen(false);
          setError(null);
          inviteForm.reset();
        }}
        title={t('members.inviteTitle')}
      >
        <form
          onSubmit={inviteForm.onSubmit((values) => inviteMutation.mutate(values))}
          data-testid="invite-form"
        >
          <Stack>
            <Text size="sm" c="dimmed">
              {t('members.inviteHint')}
            </Text>
            <TextInput
              label={t('members.email')}
              placeholder={t('members.emailPlaceholder')}
              {...inviteForm.getInputProps('email')}
              disabled={inviteMutation.isPending}
            />
            <Select
              label={t('members.role')}
              data={ROLE_OPTIONS.map((opt) => ({ value: opt.value, label: t(opt.labelKey) }))}
              value={inviteForm.values.role}
              onChange={(v) => inviteForm.setFieldValue('role', (v ?? 'member') as MemberRole)}
              disabled={inviteMutation.isPending}
            />
            {error ? (
              <Alert color="red" variant="light">
                {error}
              </Alert>
            ) : null}
            <Group justify="flex-end">
              <Button
                variant="default"
                onClick={() => {
                  setInviteOpen(false);
                  setError(null);
                  inviteForm.reset();
                }}
                disabled={inviteMutation.isPending}
              >
                {t('members.cancel')}
              </Button>
              <Button type="submit" loading={inviteMutation.isPending}>
                {t('members.send')}
              </Button>
            </Group>
          </Stack>
        </form>
      </Modal>

      <Modal
        opened={confirmRemove !== null}
        onClose={() => setConfirmRemove(null)}
        title={confirmRemove ? t('members.removeTitle', { email: confirmRemove.email }) : ''}
      >
        <Stack>
          <Text size="sm">{t('members.removeBody')}</Text>
          <Group justify="flex-end">
            <Button variant="default" onClick={() => setConfirmRemove(null)}>
              {t('members.cancel')}
            </Button>
            <Button
              color="red"
              loading={removeMutation.isPending}
              onClick={() => confirmRemove && removeMutation.mutate(confirmRemove.userId)}
            >
              {t('members.removeConfirm')}
            </Button>
          </Group>
        </Stack>
      </Modal>

      <Modal
        opened={confirmLeave}
        onClose={() => setConfirmLeave(false)}
        title={t('members.leaveTitle')}
      >
        <Stack>
          <Text size="sm">{t('members.leaveBody')}</Text>
          <Group justify="flex-end">
            <Button variant="default" onClick={() => setConfirmLeave(false)}>
              {t('members.leaveCancel')}
            </Button>
            <Button
              color="red"
              loading={removeMutation.isPending}
              onClick={() => currentUserId && removeMutation.mutate(currentUserId)}
            >
              {t('members.leaveAction')}
            </Button>
          </Group>
        </Stack>
      </Modal>
    </Stack>
  );
}

function capitalize(s: string): string {
  return s.charAt(0).toUpperCase() + s.slice(1);
}
