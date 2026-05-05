import { AppShell, Burger, Group, NavLink, ScrollArea, Title } from '@mantine/core';
import { useDisclosure } from '@mantine/hooks';
import { IconAlertTriangle, IconHome, IconSettings, IconShieldLock } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { Link, Outlet, useParams } from 'react-router';

export function AppShellLayout() {
  const [opened, { toggle }] = useDisclosure();
  const { t } = useTranslation();
  const { orgSlug = 'demo', projectSlug } = useParams();

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
