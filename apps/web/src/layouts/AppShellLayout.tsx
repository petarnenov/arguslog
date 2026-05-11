import {
  ActionIcon,
  Alert,
  AppShell,
  Burger,
  Button,
  Divider,
  Group,
  Menu,
  Modal,
  NavLink,
  ScrollArea,
  Stack,
  Text,
  TextInput,
  Title,
  UnstyledButton,
} from '@mantine/core';
import { useDisclosure } from '@mantine/hooks';
import {
  IconAlertTriangle,
  IconBell,
  IconBuilding,
  IconCheck,
  IconChevronDown,
  IconCreditCard,
  IconFolders,
  IconKey,
  IconLogout,
  IconPlus,
  IconSend,
  IconSettings,
  IconShieldLock,
  IconTag,
  IconTrash,
  IconUser,
  IconUsers,
} from '@tabler/icons-react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, Outlet, useNavigate, useParams } from 'react-router';

import { ApiError } from '../api/client';
import { deleteOrg } from '../api/orgs';
import { queryKeys, useMe, useMyOrgs, useProjects } from '../api/queries';
import { BonusBanner } from '../components/BonusBanner';
import { useAuth } from '../auth/useAuth';
import { DevErrorMenu } from '../components/DevErrorMenu';

export function AppShellLayout() {
  const [opened, { toggle }] = useDisclosure();
  const { t } = useTranslation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { orgSlug: urlOrgSlug, projectId } = useParams();
  const orgs = useMyOrgs();
  // When the URL doesn't carry an org slug yet (e.g. /onboarding), fall back to the user's first
  // org. This keeps the sidebar's "Issues" link pointing somewhere real instead of the previous
  // hard-coded 'demo' default which sent users to an org they don't belong to.
  const orgSlug = urlOrgSlug ?? orgs.data?.[0]?.slug;
  const currentOrg = orgs.data?.find((o) => o.slug === orgSlug);
  const projects = useProjects(currentOrg?.id, { enabled: Boolean(currentOrg && projectId) });
  const me = useMe();
  const currentProject = projectId
    ? projects.data?.find((p) => String(p.id) === projectId)
    : undefined;
  const { user, signOut } = useAuth();
  const userLabel = user?.name ?? user?.email ?? user?.id;

  const [deleteOpen, setDeleteOpen] = useState(false);
  const [confirmName, setConfirmName] = useState('');
  const [deleteError, setDeleteError] = useState<string | null>(null);

  const deleteMutation = useMutation({
    mutationFn: () => {
      if (!currentOrg) throw new Error('no current org');
      return deleteOrg(currentOrg.id);
    },
    onSuccess: async () => {
      // Both the user's own org list AND every cached admin view (orgs list, stats counters,
      // future audit entries) reference this org by id, so invalidate the whole admin prefix.
      // Without this, a platform admin who navigates to /admin/orgs after deleting their own
      // org sees the deleted row until React Query's 30s staleTime elapses (GH #42).
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: queryKeys.myOrgs() }),
        queryClient.invalidateQueries({ queryKey: ['admin'] }),
      ]);
      setDeleteOpen(false);
      setConfirmName('');
      setDeleteError(null);
      // Land on /orgs — the landing page redirects to /onboarding if no orgs left,
      // or the user's first remaining org otherwise.
      navigate('/orgs', { replace: true });
    },
    onError: (err: unknown) => {
      setDeleteError(
        err instanceof ApiError ? (err.problem.detail ?? err.problem.title) : String(err),
      );
    },
  });

  const canDelete = Boolean(currentOrg) && confirmName.trim() === (currentOrg?.name ?? '');

  return (
    <AppShell
      header={{ height: 56 }}
      navbar={{ width: 240, breakpoint: 'sm', collapsed: { mobile: !opened } }}
      padding="md"
    >
      <AppShell.Header>
        <Group h="100%" px="md" justify="space-between">
          <Group gap="xs" wrap="nowrap" style={{ minWidth: 0 }}>
            <Burger opened={opened} onClick={toggle} hiddenFrom="sm" size="sm" />
            <Title order={4} style={{ whiteSpace: 'nowrap' }}>
              {t('app.name')}
            </Title>
            {currentOrg && (
              <Text size="sm" c="dimmed" truncate data-testid="header-context">
                {' / '}
                {currentOrg.name}
                {currentProject ? ` / ${currentProject.name}` : ''}
              </Text>
            )}
          </Group>
          {user && (
            <Group gap="xs">
              <DevErrorMenu />
              <Menu position="bottom-end" withArrow>
                <Menu.Target>
                  <ActionIcon variant="subtle" aria-label={userLabel} size="lg">
                    <IconUser size={18} />
                  </ActionIcon>
                </Menu.Target>
                <Menu.Dropdown>
                  <Menu.Label>
                    <Text size="xs" c="dimmed">
                      {t('auth.signedInAs', { name: userLabel })}
                    </Text>
                  </Menu.Label>
                  <Menu.Item component={Link} to="/billing" leftSection={<IconCreditCard size={14} />}>
                    {t('nav.billing')}
                  </Menu.Item>
                  <Menu.Item component={Link} to="/me/tokens" leftSection={<IconKey size={14} />}>
                    {t('nav.tokens')}
                  </Menu.Item>
                  <Menu.Item leftSection={<IconLogout size={14} />} onClick={() => void signOut()}>
                    {t('auth.logout')}
                  </Menu.Item>
                </Menu.Dropdown>
              </Menu>
            </Group>
          )}
        </Group>
      </AppShell.Header>

      <AppShell.Navbar p="xs">
        <AppShell.Section>
          <Menu position="bottom-start" shadow="md" width={220} withArrow>
            <Menu.Target>
              <UnstyledButton
                aria-label={t('orgSwitcher.label')}
                style={{
                  width: '100%',
                  padding: '8px 10px',
                  borderRadius: 6,
                }}
              >
                <Group gap="xs" wrap="nowrap">
                  <IconBuilding size={18} />
                  <Stack gap={0} style={{ flex: 1, minWidth: 0 }}>
                    <Text size="xs" c="dimmed">
                      {t('orgSwitcher.label')}
                    </Text>
                    <Text size="sm" fw={600} truncate>
                      {currentOrg?.name ?? t('orgSwitcher.noneSelected')}
                    </Text>
                  </Stack>
                  <IconChevronDown size={14} />
                </Group>
              </UnstyledButton>
            </Menu.Target>
            <Menu.Dropdown>
              {orgs.data?.map((org) => (
                <Menu.Item
                  key={org.id}
                  component={Link}
                  to={`/orgs/${org.slug}/projects`}
                  leftSection={
                    org.slug === orgSlug ? (
                      <IconCheck size={14} />
                    ) : (
                      <span style={{ width: 14, display: 'inline-block' }} />
                    )
                  }
                >
                  {org.name}
                </Menu.Item>
              ))}
              {orgs.data && orgs.data.length > 0 && <Menu.Divider />}
              <Menu.Item component={Link} to="/onboarding" leftSection={<IconPlus size={14} />}>
                {t('orgSwitcher.create')}
              </Menu.Item>
              {currentOrg && (
                <Menu.Item
                  color="red"
                  leftSection={<IconTrash size={14} />}
                  onClick={() => {
                    setDeleteError(null);
                    setConfirmName('');
                    setDeleteOpen(true);
                  }}
                >
                  {t('orgSwitcher.delete')}
                </Menu.Item>
              )}
            </Menu.Dropdown>
          </Menu>
          {me.data?.bonusUntil && (
            // Per-user billing (V26+): the banner is keyed off the signed-in user's bonus, not
            // the currently-selected org. Users with multiple orgs see the same banner across
            // them; users with no current org context (e.g. /onboarding) still see it.
            <BonusBanner
              bonus={{
                until: me.data.bonusUntil,
                reason: me.data.bonusReason ?? null,
                grantedByEmail: null,
              }}
              plan={me.data.plan}
              variant="compact"
            />
          )}
          <Divider my="xs" />
        </AppShell.Section>
        <AppShell.Section grow component={ScrollArea}>
          {orgSlug && (
            <NavLink
              component={Link}
              to={`/orgs/${orgSlug}/projects`}
              label={t('nav.projects')}
              leftSection={<IconFolders size={16} />}
            />
          )}
          {orgSlug && (
            <NavLink
              component={Link}
              to={`/orgs/${orgSlug}/settings/destinations`}
              label={t('nav.destinations')}
              leftSection={<IconSend size={16} />}
            />
          )}
          {orgSlug && (
            <NavLink
              component={Link}
              to={`/orgs/${orgSlug}/settings`}
              label={t('nav.members')}
              leftSection={<IconUsers size={16} />}
            />
          )}
          <NavLink
            component={Link}
            to="/billing"
            label={t('nav.billing')}
            leftSection={<IconCreditCard size={16} />}
          />
          {me.data?.isPlatformAdmin && (
            <NavLink
              component={Link}
              to="/admin"
              label={t('nav.admin')}
              leftSection={<IconShieldLock size={16} />}
              data-testid="nav-admin"
            />
          )}
          {orgSlug && projectId && (
            <>
              <Divider my="xs" label={t('projectSwitcher.label')} labelPosition="left" />
              <Group gap="xs" wrap="nowrap" px={10} pb={6} aria-label={t('projectSwitcher.label')}>
                <IconFolders size={16} />
                <Stack gap={0} style={{ flex: 1, minWidth: 0 }}>
                  <Text size="sm" fw={600} truncate data-testid="current-project-name">
                    {projects.isLoading
                      ? t('projectSwitcher.loading')
                      : (currentProject?.name ?? t('projectSwitcher.unknown'))}
                  </Text>
                  {currentProject?.slug ? (
                    <Text size="xs" c="dimmed" truncate>
                      {currentProject.slug}
                    </Text>
                  ) : null}
                </Stack>
              </Group>
              <NavLink
                component={Link}
                to={`/orgs/${orgSlug}/projects/${projectId}/issues`}
                label={t('issues.title')}
                leftSection={<IconAlertTriangle size={16} />}
              />
              <NavLink
                component={Link}
                to={`/orgs/${orgSlug}/projects/${projectId}/alert-rules`}
                label={t('nav.alertRules')}
                leftSection={<IconBell size={16} />}
              />
              <NavLink
                component={Link}
                to={`/orgs/${orgSlug}/projects/${projectId}/releases`}
                label={t('nav.releases')}
                leftSection={<IconTag size={16} />}
              />
              <NavLink
                component={Link}
                to={`/orgs/${orgSlug}/projects/${projectId}/settings`}
                label={t('nav.settings')}
                leftSection={<IconSettings size={16} />}
              />
              <NavLink
                component={Link}
                to={`/orgs/${orgSlug}/projects/${projectId}/settings/keys`}
                label="Keys"
                leftSection={<IconShieldLock size={16} />}
              />
            </>
          )}
        </AppShell.Section>
      </AppShell.Navbar>

      <AppShell.Main>
        <Outlet />
      </AppShell.Main>

      <Modal
        opened={deleteOpen}
        onClose={() => {
          if (!deleteMutation.isPending) {
            setDeleteOpen(false);
            setConfirmName('');
            setDeleteError(null);
          }
        }}
        title={t('orgSwitcher.deleteTitle', { name: currentOrg?.name ?? '' })}
        size="md"
      >
        <Stack>
          <Text size="sm" c="red.7" fw={500}>
            {t('orgSwitcher.deleteWarning')}
          </Text>
          <Text size="sm">{t('orgSwitcher.deleteBody')}</Text>
          <TextInput
            label={t('orgSwitcher.deleteConfirmLabel', { name: currentOrg?.name ?? '' })}
            value={confirmName}
            onChange={(e) => setConfirmName(e.currentTarget.value)}
            disabled={deleteMutation.isPending}
            data-autofocus
          />
          {deleteError ? (
            <Alert color="red" variant="light">
              {deleteError}
            </Alert>
          ) : null}
          <Group justify="flex-end">
            <Button
              variant="default"
              onClick={() => setDeleteOpen(false)}
              disabled={deleteMutation.isPending}
            >
              {t('orgSwitcher.deleteCancel')}
            </Button>
            <Button
              color="red"
              loading={deleteMutation.isPending}
              disabled={!canDelete}
              onClick={() => deleteMutation.mutate()}
            >
              {t('orgSwitcher.deleteConfirm')}
            </Button>
          </Group>
        </Stack>
      </Modal>
    </AppShell>
  );
}
