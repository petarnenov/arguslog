import {
  ActionIcon,
  AppShell,
  Burger,
  Divider,
  Group,
  Menu,
  NavLink,
  ScrollArea,
  Stack,
  Text,
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
  IconHome,
  IconLogout,
  IconPlus,
  IconSend,
  IconSettings,
  IconShieldLock,
  IconUser,
} from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { Link, Outlet, useParams } from 'react-router';

import { useMyOrgs } from '../api/queries';
import { useAuth } from '../auth/useAuth';

export function AppShellLayout() {
  const [opened, { toggle }] = useDisclosure();
  const { t } = useTranslation();
  const { orgSlug: urlOrgSlug, projectId } = useParams();
  const orgs = useMyOrgs();
  // When the URL doesn't carry an org slug yet (e.g. /onboarding), fall back to the user's first
  // org. This keeps the sidebar's "Issues" link pointing somewhere real instead of the previous
  // hard-coded 'demo' default which sent users to an org they don't belong to.
  const orgSlug = urlOrgSlug ?? orgs.data?.[0]?.slug;
  const currentOrg = orgs.data?.find((o) => o.slug === orgSlug);
  const { user, signOut } = useAuth();
  const userLabel = user?.name ?? user?.email ?? user?.id;

  return (
    <AppShell
      header={{ height: 56 }}
      navbar={{ width: 240, breakpoint: 'sm', collapsed: { mobile: !opened } }}
      padding="md"
    >
      <AppShell.Header>
        <Group h="100%" px="md" justify="space-between">
          <Group>
            <Burger opened={opened} onClick={toggle} hiddenFrom="sm" size="sm" />
            <Title order={4}>{t('app.name')}</Title>
          </Group>
          {user && (
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
                <Menu.Item leftSection={<IconLogout size={14} />} onClick={() => void signOut()}>
                  {t('auth.logout')}
                </Menu.Item>
              </Menu.Dropdown>
            </Menu>
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
              <Menu.Item
                component={Link}
                to="/onboarding"
                leftSection={<IconPlus size={14} />}
              >
                {t('orgSwitcher.create')}
              </Menu.Item>
            </Menu.Dropdown>
          </Menu>
          <Divider my="xs" />
        </AppShell.Section>
        <AppShell.Section grow component={ScrollArea}>
          {orgSlug && (
            <NavLink
              component={Link}
              to={`/orgs/${orgSlug}/projects`}
              label={t('nav.issues')}
              leftSection={<IconHome size={16} />}
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
          {orgSlug && projectId && (
            <>
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
    </AppShell>
  );
}
