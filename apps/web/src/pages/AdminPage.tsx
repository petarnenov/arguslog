import {
  Alert,
  Badge,
  Button,
  Card,
  Code,
  Group,
  Loader,
  Modal,
  NumberFormatter,
  Pagination,
  Select,
  SimpleGrid,
  Stack,
  Table,
  Tabs,
  Text,
  TextInput,
  Textarea,
  Title,
} from '@mantine/core';
import { useForm } from '@mantine/form';
import { IconGift, IconSearch, IconShieldLock, IconTrash } from '@tabler/icons-react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Navigate } from 'react-router';

import {
  grantUserTier,
  revokeUserTier,
  type AdminUser,
  type AdminOrg,
  type GrantMonths,
  type GrantTier,
} from '../api/admin';
import { ApiError } from '../api/client';
import { useAdminAudit, useAdminOrgs, useAdminStats, useAdminUsers, useMe } from '../api/queries';
import { formatRelativeTime } from '../lib/relativeTime';

const PAGE_SIZE = 25;

export function AdminPage() {
  const { t } = useTranslation();
  const me = useMe();

  if (me.isLoading) {
    return (
      <Group p="md">
        <Loader size="sm" />
      </Group>
    );
  }
  if (!me.data?.isPlatformAdmin) {
    return <Navigate to="/" replace />;
  }

  return (
    <Stack maw={1400}>
      <Group gap="sm">
        <IconShieldLock size={28} />
        <Title order={3}>{t('admin.title')}</Title>
        <Badge variant="light" color="red">
          {t('admin.adminBadge')}
        </Badge>
      </Group>
      <Text c="dimmed" size="sm">
        {t('admin.subtitle', { email: me.data.email ?? '' })}
      </Text>

      <Tabs defaultValue="overview">
        <Tabs.List>
          <Tabs.Tab value="overview">{t('admin.tabOverview')}</Tabs.Tab>
          <Tabs.Tab value="users">{t('admin.tabUsers')}</Tabs.Tab>
          <Tabs.Tab value="orgs">{t('admin.tabOrgs')}</Tabs.Tab>
          <Tabs.Tab value="audit">{t('admin.tabAudit')}</Tabs.Tab>
        </Tabs.List>

        <Tabs.Panel value="overview" pt="md">
          <OverviewPanel />
        </Tabs.Panel>
        <Tabs.Panel value="users" pt="md">
          <UsersPanel />
        </Tabs.Panel>
        <Tabs.Panel value="orgs" pt="md">
          <OrgsPanel />
        </Tabs.Panel>
        <Tabs.Panel value="audit" pt="md">
          <AuditPanel />
        </Tabs.Panel>
      </Tabs>
    </Stack>
  );
}

function OverviewPanel() {
  const { t } = useTranslation();
  const stats = useAdminStats();

  if (stats.isLoading) return <Loader size="sm" />;
  if (stats.isError || !stats.data) {
    return (
      <Alert color="red" variant="light">
        {t('errors.generic')}
      </Alert>
    );
  }
  const data = stats.data;

  return (
    <Stack>
      <SimpleGrid cols={{ base: 2, sm: 4 }} spacing="sm">
        <StatCard label={t('admin.statUsers')} value={data.totalUsers} />
        <StatCard label={t('admin.statOrgs')} value={data.totalOrgs} />
        <StatCard label={t('admin.statProjects')} value={data.totalProjects} />
        <StatCard label={t('admin.statIssues')} value={data.totalIssues} />
        <StatCard label={t('admin.statEvents7d')} value={data.events7d} />
        <StatCard label={t('admin.statEvents30d')} value={data.events30d} />
        <StatCard label={t('admin.statBonuses')} value={data.activeBonusGrants} accent="violet" />
      </SimpleGrid>
      <Card withBorder padding="lg" radius="md">
        <Stack gap="sm">
          <Text fw={600}>{t('admin.byPlan')}</Text>
          <Group gap="md">
            {Object.entries(data.orgsByPlan).map(([plan, count]) => (
              <Badge key={plan} variant="light" size="lg" color={planColor(plan)}>
                {plan} · {count}
              </Badge>
            ))}
          </Group>
        </Stack>
      </Card>
    </Stack>
  );
}

function StatCard({ label, value, accent }: { label: string; value: number; accent?: string }) {
  return (
    <Card withBorder padding="md" radius="md" data-testid={`stat-${label}`}>
      <Stack gap={2}>
        <Text size="xs" c="dimmed" tt="uppercase" fw={600}>
          {label}
        </Text>
        <Title order={3} c={accent}>
          <NumberFormatter value={value} thousandSeparator />
        </Title>
      </Stack>
    </Card>
  );
}

function UsersPanel() {
  const { t, i18n } = useTranslation();
  const [q, setQ] = useState('');
  const [page, setPage] = useState(1);
  const [userGrantTarget, setUserGrantTarget] = useState<AdminUser | null>(null);
  const offset = (page - 1) * PAGE_SIZE;
  const users = useAdminUsers(q, offset, PAGE_SIZE);
  const total = users.data?.total ?? 0;
  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));

  return (
    <Stack>
      <Group>
        <TextInput
          placeholder={t('admin.searchUsers')}
          leftSection={<IconSearch size={14} />}
          value={q}
          onChange={(e) => {
            setQ(e.currentTarget.value);
            setPage(1);
          }}
          style={{ flex: 1, maxWidth: 360 }}
        />
        <Text c="dimmed" size="sm">
          {t('admin.totalUsers', { count: total })}
        </Text>
      </Group>
      {users.isLoading ? (
        <Loader size="sm" />
      ) : (
        <Card withBorder padding="0" radius="md">
          <Table striped highlightOnHover>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('admin.colEmail')}</Table.Th>
                <Table.Th>{t('admin.colName')}</Table.Th>
                <Table.Th>{t('admin.colOwned')}</Table.Th>
                <Table.Th>{t('admin.colMember')}</Table.Th>
                <Table.Th>{t('admin.colPlan')}</Table.Th>
                <Table.Th>{t('admin.colCreated')}</Table.Th>
                <Table.Th style={{ textAlign: 'right' }}>{t('admin.colActions')}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {users.data?.items.map((u) => (
                <Table.Tr key={u.userId}>
                  <Table.Td>
                    <Text size="sm">{u.email ?? '—'}</Text>
                  </Table.Td>
                  <Table.Td>
                    <Text size="sm" c="dimmed">
                      {u.displayName ?? '—'}
                    </Text>
                  </Table.Td>
                  <Table.Td>{u.ownedOrgs}</Table.Td>
                  <Table.Td>{u.memberOrgs}</Table.Td>
                  <Table.Td>
                    {u.highestPlan ? (
                      <Badge variant="light" color={planColor(u.highestPlan)}>
                        {u.highestPlan}
                      </Badge>
                    ) : (
                      <Text c="dimmed" size="xs">
                        —
                      </Text>
                    )}
                  </Table.Td>
                  <Table.Td>
                    <Text size="xs" c="dimmed">
                      {formatRelativeTime(u.createdAt, i18n.language || 'en')}
                    </Text>
                  </Table.Td>
                  <Table.Td>
                    <Group gap="xs" justify="flex-end">
                      <Button
                        size="xs"
                        variant="light"
                        leftSection={<IconGift size={12} />}
                        onClick={() => setUserGrantTarget(u)}
                      >
                        {t('admin.grantAction')}
                      </Button>
                      {u.highestPlan && u.highestPlan !== 'free' && (
                        <UserRevokeButton userId={u.userId} />
                      )}
                    </Group>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Card>
      )}
      <UserGrantModal user={userGrantTarget} onClose={() => setUserGrantTarget(null)} />
      {totalPages > 1 && (
        <Group justify="center">
          <Pagination total={totalPages} value={page} onChange={setPage} />
        </Group>
      )}
    </Stack>
  );
}

function OrgsPanel() {
  const { t, i18n } = useTranslation();
  const [q, setQ] = useState('');
  const [page, setPage] = useState(1);
  const offset = (page - 1) * PAGE_SIZE;
  const orgs = useAdminOrgs(q, offset, PAGE_SIZE);
  const total = orgs.data?.total ?? 0;
  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));

  return (
    <Stack>
      <Group>
        <TextInput
          placeholder={t('admin.searchOrgs')}
          leftSection={<IconSearch size={14} />}
          value={q}
          onChange={(e) => {
            setQ(e.currentTarget.value);
            setPage(1);
          }}
          style={{ flex: 1, maxWidth: 360 }}
        />
        <Text c="dimmed" size="sm">
          {t('admin.totalOrgs', { count: total })}
        </Text>
      </Group>
      {orgs.isLoading ? (
        <Loader size="sm" />
      ) : (
        <Card withBorder padding="0" radius="md">
          <Table striped highlightOnHover>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('admin.colSlug')}</Table.Th>
                <Table.Th>{t('admin.colOwner')}</Table.Th>
                <Table.Th>{t('admin.colPlan')}</Table.Th>
                <Table.Th>{t('admin.colProjects')}</Table.Th>
                <Table.Th>{t('admin.colMembers')}</Table.Th>
                <Table.Th>{t('admin.colEvents30d')}</Table.Th>
                <Table.Th>{t('admin.colBonus')}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {orgs.data?.items.map((o) => (
                <Table.Tr key={o.orgId}>
                  <Table.Td>
                    <Stack gap={0}>
                      <Text size="sm" fw={500}>
                        {o.name}
                      </Text>
                      <Code style={{ fontSize: 10 }}>{o.slug}</Code>
                    </Stack>
                  </Table.Td>
                  <Table.Td>
                    <Text size="xs" c="dimmed">
                      {o.ownerEmail ?? '—'}
                    </Text>
                  </Table.Td>
                  <Table.Td>
                    <Badge variant="light" color={planColor(o.tier)}>
                      {o.tier}
                    </Badge>
                  </Table.Td>
                  <Table.Td>{o.projects}</Table.Td>
                  <Table.Td>{o.members}</Table.Td>
                  <Table.Td>
                    <NumberFormatter value={o.events30d} thousandSeparator />
                  </Table.Td>
                  <Table.Td>
                    {o.tierExpiresAt ? (
                      <Badge color="violet" variant="light" leftSection={<IconGift size={10} />}>
                        {formatRelativeTime(o.tierExpiresAt, i18n.language || 'en')}
                      </Badge>
                    ) : (
                      <Text c="dimmed" size="xs">
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
      {totalPages > 1 && (
        <Group justify="center">
          <Pagination total={totalPages} value={page} onChange={setPage} />
        </Group>
      )}
    </Stack>
  );
}

interface GrantFormValues {
  tier: GrantTier;
  months: GrantMonths;
  reason: string;
}

function UserRevokeButton({ userId }: { userId: string }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const m = useMutation({
    mutationFn: () => revokeUserTier(userId),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['admin'] });
    },
  });
  return (
    <Button
      size="xs"
      variant="subtle"
      color="red"
      leftSection={<IconTrash size={12} />}
      loading={m.isPending}
      onClick={() => m.mutate()}
    >
      {t('admin.revokeAction')}
    </Button>
  );
}

function UserGrantModal({ user, onClose }: { user: AdminUser | null; onClose: () => void }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const form = useForm<GrantFormValues>({
    initialValues: { tier: 'gold', months: 0, reason: '' },
  });
  const mutation = useMutation({
    mutationFn: async (values: GrantFormValues) => {
      if (!user) throw new Error('no user');
      await grantUserTier(user.userId, values);
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['admin'] });
      form.reset();
      onClose();
    },
  });
  const error =
    mutation.error instanceof ApiError
      ? (mutation.error.problem.detail ?? mutation.error.problem.title)
      : null;

  return (
    <Modal
      opened={user !== null}
      onClose={onClose}
      title={t('admin.grantTitle', { name: user?.email ?? user?.displayName ?? '' })}
      size="md"
    >
      <form onSubmit={form.onSubmit((values) => mutation.mutate(values))}>
        <Stack>
          <Select
            label={t('admin.grantTierLabel')}
            data={[
              { value: 'silver', label: 'Silver' },
              { value: 'gold', label: 'Gold' },
              { value: 'platinum', label: 'Platinum' },
            ]}
            {...form.getInputProps('tier')}
          />
          <Select
            label={t('admin.grantMonthsLabel')}
            data={[
              { value: '0', label: 'Permanent (no auto-expiry)' },
              { value: '1', label: '1 month' },
              { value: '3', label: '3 months' },
              { value: '6', label: '6 months' },
              { value: '12', label: '12 months' },
            ]}
            value={String(form.values.months)}
            onChange={(v) => form.setFieldValue('months', Number(v) as GrantMonths)}
          />
          <Textarea
            label={t('admin.grantReasonLabel')}
            description={t('admin.grantReasonHint')}
            minRows={2}
            {...form.getInputProps('reason')}
          />
          {error && (
            <Alert color="red" variant="light">
              {error}
            </Alert>
          )}
          <Group justify="flex-end">
            <Button variant="default" onClick={onClose} disabled={mutation.isPending}>
              {t('admin.cancel')}
            </Button>
            <Button type="submit" loading={mutation.isPending}>
              {t('admin.grantConfirm')}
            </Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}

function AuditPanel() {
  const { t, i18n } = useTranslation();
  const [page, setPage] = useState(1);
  const offset = (page - 1) * PAGE_SIZE;
  const audit = useAdminAudit(offset, PAGE_SIZE);
  const total = audit.data?.total ?? 0;
  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));

  if (audit.isLoading) return <Loader size="sm" />;
  return (
    <Stack>
      <Card withBorder padding="0" radius="md">
        <Table striped>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>{t('admin.colWhen')}</Table.Th>
              <Table.Th>{t('admin.colAdmin')}</Table.Th>
              <Table.Th>{t('admin.colAction')}</Table.Th>
              <Table.Th>{t('admin.colTarget')}</Table.Th>
              <Table.Th>{t('admin.colPayload')}</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {audit.data?.items.map((e) => (
              <Table.Tr key={e.id}>
                <Table.Td>
                  <Text size="xs" c="dimmed">
                    {formatRelativeTime(e.ts, i18n.language || 'en')}
                  </Text>
                </Table.Td>
                <Table.Td>
                  <Text size="xs">{e.adminEmail}</Text>
                </Table.Td>
                <Table.Td>
                  <Badge variant="light" size="sm">
                    {e.action}
                  </Badge>
                </Table.Td>
                <Table.Td>
                  <Code style={{ fontSize: 10 }}>
                    {e.targetType}/{e.targetId}
                  </Code>
                </Table.Td>
                <Table.Td>
                  <Code
                    style={{
                      fontSize: 10,
                      maxWidth: 380,
                      display: 'block',
                      whiteSpace: 'pre-wrap',
                    }}
                  >
                    {JSON.stringify(e.payload, null, 0)}
                  </Code>
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      </Card>
      {totalPages > 1 && (
        <Group justify="center">
          <Pagination total={totalPages} value={page} onChange={setPage} />
        </Group>
      )}
    </Stack>
  );
}

function planColor(tier: string): string {
  switch (tier) {
    case 'regular':
      return 'gray';
    case 'silver':
      return 'blue';
    case 'gold':
      return 'yellow';
    case 'platinum':
      return 'violet';
    default:
      return 'gray';
  }
}
