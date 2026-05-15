import { Anchor, AppShell, Button, Container, Group, Title } from '@mantine/core';
import { IconBrandGithub } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';

import { env } from '../env';

import { ThemeToggle } from './ThemeToggle';

const GITHUB_URL = 'https://github.com/petarnenov/arguslog';

/**
 * Shared header for the landing + status pages. Hosts the theme toggle so a visitor on either
 * page can flip the scheme without opening the dashboard. CTA buttons render only when an
 * `onboardingUrl` is supplied — the status page passes neither and gets a leaner header.
 */
export function LandingHeader({
  showCtas = false,
}: {
  showCtas?: boolean;
}) {
  const { t } = useTranslation();
  const onboardingUrl = `${env.VITE_APP_BASE_URL}/onboarding`;
  const dashboardUrl = env.VITE_APP_BASE_URL;

  return (
    <AppShell.Header>
      <Container size="lg" h="100%">
        <Group h="100%" justify="space-between">
          <Group gap="sm">
            <img src="/arguslog.svg" alt="" width={28} height={28} />
            <Title order={4} fw={700}>
              {t('app.name')}
            </Title>
          </Group>
          <Group gap="xs">
            <ThemeToggle />
            <Anchor
              href={GITHUB_URL}
              target="_blank"
              rel="noopener noreferrer"
              c="dimmed"
              size="sm"
            >
              <Group gap={4}>
                <IconBrandGithub size={16} />
                {t('nav.github')}
              </Group>
            </Anchor>
            {showCtas && (
              <>
                <Button component="a" href={dashboardUrl} variant="subtle">
                  {t('nav.signIn')}
                </Button>
                <Button component="a" href={onboardingUrl}>
                  {t('nav.getStarted')}
                </Button>
              </>
            )}
          </Group>
        </Group>
      </Container>
    </AppShell.Header>
  );
}
