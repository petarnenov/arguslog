import {
  ActionIcon,
  AppShell,
  Burger,
  Group,
  Menu,
  NavLink,
  ScrollArea,
  Text,
  Title,
} from '@mantine/core';
import { useDisclosure } from '@mantine/hooks';
import {
  IconAlertTriangle,
  IconHome,
  IconLogout,
  IconSettings,
  IconShieldLock,
  IconUser,
} from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { Link, Outlet, useParams } from 'react-router';

import { useAuth } from '../auth/useAuth';

export function AppShellLayout() {
  const [opened, { toggle }] = useDisclosure();
  const { t } = useTranslation();
  const { orgSlug = 'demo', projectSlug } = useParams();
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
        <AppShell.Section grow component={ScrollArea}>
          <NavLink
            component={Link}
            to={`/orgs/${orgSlug}/projects`}
            label={t('nav.issues')}
            leftSection={<IconHome size={16} />}
          />
          {projectSlug && (
            <>
              <NavLink
                component={Link}
                to={`/orgs/${orgSlug}/projects/${projectSlug}/issues`}
                label={t('issues.title')}
                leftSection={<IconAlertTriangle size={16} />}
              />
              <NavLink
                component={Link}
                to={`/orgs/${orgSlug}/projects/${projectSlug}/settings`}
                label={t('nav.settings')}
                leftSection={<IconSettings size={16} />}
              />
              <NavLink
                component={Link}
                to={`/orgs/${orgSlug}/projects/${projectSlug}/settings/keys`}
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
