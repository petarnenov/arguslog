import {
  Anchor,
  AppShell,
  Badge,
  Box,
  Button,
  Card,
  Center,
  Code,
  Container,
  Divider,
  Group,
  Loader,
  SimpleGrid,
  Stack,
  Text,
  ThemeIcon,
  Title,
} from '@mantine/core';
import {
  IconAlertCircle,
  IconBolt,
  IconBrandGithub,
  IconCheck,
  IconCode,
  IconCoin,
  IconRobot,
  IconRoute,
  IconTerminal,
  IconUsers,
} from '@tabler/icons-react';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';

import { listPlatforms } from '../api/platforms';
import { env } from '../env';

const GITHUB_URL = 'https://github.com/petarnenov/arguslog';

export function LandingPage() {
  const { t } = useTranslation();
  const platformsQuery = useQuery({ queryKey: ['platforms'], queryFn: listPlatforms });

  const onboardingUrl = `${env.VITE_APP_BASE_URL}/onboarding`;
  const dashboardUrl = env.VITE_APP_BASE_URL;

  return (
    <AppShell header={{ height: 64 }} padding={0}>
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
              <Button component="a" href={dashboardUrl} variant="subtle">
                {t('nav.signIn')}
              </Button>
              <Button component="a" href={onboardingUrl}>
                {t('nav.getStarted')}
              </Button>
            </Group>
          </Group>
        </Container>
      </AppShell.Header>

      <AppShell.Main>
        <Hero onboardingUrl={onboardingUrl} />
        <Features />
        <Platforms platforms={platformsQuery.data ?? []} loading={platformsQuery.isLoading} />
        <McpSection />
        <FooterSection dashboardUrl={dashboardUrl} />
      </AppShell.Main>
    </AppShell>
  );
}

function Hero({ onboardingUrl }: { onboardingUrl: string }) {
  const { t } = useTranslation();
  return (
    <Box py={{ base: 64, sm: 96 }}>
      <Container size="lg">
        <Stack align="center" gap="lg">
          <Badge variant="light" size="lg" radius="sm">
            {t('hero.tagline')}
          </Badge>
          <Title order={1} ta="center" fw={800} size="3rem" lh={1.1}>
            {t('hero.title')}
          </Title>
          <Text size="xl" c="dimmed" ta="center" maw={760}>
            {t('hero.subtitle')}
          </Text>
          <Group gap="md" mt="md">
            <Button component="a" href={onboardingUrl} size="lg">
              {t('hero.ctaPrimary')}
            </Button>
            <Button
              component="a"
              href={GITHUB_URL}
              target="_blank"
              rel="noopener noreferrer"
              size="lg"
              variant="default"
              leftSection={<IconBrandGithub size={18} />}
            >
              {t('hero.ctaSecondary')}
            </Button>
          </Group>
        </Stack>
      </Container>
    </Box>
  );
}

const FEATURE_ICONS = {
  ingest: IconBolt,
  sourcemaps: IconCode,
  breadcrumbs: IconRoute,
  web3: IconCoin,
  alerts: IconAlertCircle,
  team: IconUsers,
} as const;

function Features() {
  const { t } = useTranslation();
  const items = (Object.keys(FEATURE_ICONS) as Array<keyof typeof FEATURE_ICONS>).map((key) => ({
    key,
    Icon: FEATURE_ICONS[key],
    title: t(`features.items.${key}.title`),
    body: t(`features.items.${key}.body`),
  }));

  return (
    <Box py={64} bg="var(--mantine-color-body)">
      <Container size="lg">
        <Stack gap="xs" mb="xl">
          <Title order={2}>{t('features.heading')}</Title>
          <Text c="dimmed" maw={680}>
            {t('features.subheading')}
          </Text>
        </Stack>
        <SimpleGrid cols={{ base: 1, sm: 2, md: 3 }} spacing="lg">
          {items.map((it) => (
            <Card key={it.key} withBorder padding="lg" radius="md">
              <Group gap="sm" mb="sm">
                <ThemeIcon variant="light" size="lg" radius="md">
                  <it.Icon size={20} />
                </ThemeIcon>
                <Title order={4}>{it.title}</Title>
              </Group>
              <Text c="dimmed">{it.body}</Text>
            </Card>
          ))}
        </SimpleGrid>
      </Container>
    </Box>
  );
}

function Platforms({
  platforms,
  loading,
}: {
  platforms: { slug: string; name: string; sdkPackage: string | null; sdkVersion: string | null }[];
  loading: boolean;
}) {
  const { t } = useTranslation();

  return (
    <Box py={64}>
      <Container size="lg">
        <Stack gap="xs" mb="xl">
          <Title order={2}>{t('platforms.heading')}</Title>
          <Text c="dimmed" maw={680}>
            {t('platforms.subheading')}
          </Text>
        </Stack>

        {loading ? (
          <Center mih={120}>
            <Loader />
          </Center>
        ) : platforms.length === 0 ? (
          <Card withBorder padding="lg">
            <Text c="dimmed">{t('platforms.fallback')}</Text>
          </Card>
        ) : (
          <SimpleGrid cols={{ base: 1, sm: 2, md: 4 }} spacing="md">
            {platforms.map((p) => (
              <Card key={p.slug} withBorder padding="md" radius="md" data-testid="platform-card">
                <Stack gap={4}>
                  <Title order={5}>{p.name}</Title>
                  {p.sdkPackage ? (
                    <Text size="xs" c="dimmed" ff="monospace">
                      {p.sdkPackage}
                      {p.sdkVersion ? `@${p.sdkVersion}` : ''}
                    </Text>
                  ) : null}
                </Stack>
              </Card>
            ))}
          </SimpleGrid>
        )}

        <Card
          withBorder
          padding="lg"
          radius="md"
          mt="lg"
          data-testid="web3-addon-card"
          style={{ borderColor: 'var(--mantine-color-violet-6)' }}
        >
          <Group gap="sm" align="flex-start" wrap="nowrap">
            <ThemeIcon variant="light" color="violet" size="lg" radius="md">
              <IconCoin size={20} />
            </ThemeIcon>
            <Stack gap={4} style={{ flex: 1 }}>
              <Group gap="sm" wrap="wrap">
                <Title order={5}>{t('platforms.web3Card.title')}</Title>
                <Text size="xs" c="dimmed" ff="monospace">
                  {t('platforms.web3Card.package')}
                </Text>
              </Group>
              <Text c="dimmed" size="sm">
                {t('platforms.web3Card.description')}
              </Text>
            </Stack>
          </Group>
        </Card>
      </Container>
    </Box>
  );
}

function McpSection() {
  const { t } = useTranslation();
  const configSnippet = `{
  "mcpServers": {
    "arguslog": {
      "command": "npx",
      "args": ["-y", "@arguslog/mcp-server"],
      "env": { "ARGUSLOG_PAT": "arglog_pat_xxx" }
    }
  }
}`;

  return (
    <Box py={64}>
      <Container size="lg">
        <Stack gap="md" mb="xl">
          <Group gap="sm">
            <ThemeIcon variant="light" color="violet" size="xl" radius="md">
              <IconRobot size={28} />
            </ThemeIcon>
            <Stack gap={0}>
              <Title order={2}>{t('mcp.heading')}</Title>
              <Text c="dimmed" size="sm">
                {t('mcp.subheading')}
              </Text>
            </Stack>
          </Group>
        </Stack>

        <SimpleGrid cols={{ base: 1, md: 2 }} spacing="lg">
          <Card withBorder padding="lg" radius="md">
            <Stack gap="md">
              <Group gap="sm">
                <ThemeIcon variant="light" size="lg" radius="md">
                  <IconTerminal size={20} />
                </ThemeIcon>
                <Title order={4}>{t('mcp.bullet1Title')}</Title>
              </Group>
              <Text c="dimmed">{t('mcp.bullet1Body')}</Text>
              <Group gap="xs">
                <Badge variant="light" color="violet">
                  @arguslog/mcp-server
                </Badge>
                <Badge variant="light" color="gray">
                  stdio
                </Badge>
                <Badge variant="light" color="gray">
                  node ≥ 20
                </Badge>
              </Group>
            </Stack>
          </Card>

          <Card withBorder padding="lg" radius="md">
            <Stack gap="md">
              <Group gap="sm">
                <ThemeIcon variant="light" color="cyan" size="lg" radius="md">
                  <IconCode size={20} />
                </ThemeIcon>
                <Title order={4}>{t('mcp.bullet2Title')}</Title>
              </Group>
              <Text c="dimmed">{t('mcp.bullet2Body')}</Text>
              <Stack gap={4}>
                {(t('mcp.coverage', { returnObjects: true }) as string[]).map((line) => (
                  <Group key={line} gap={6} wrap="nowrap">
                    <ThemeIcon variant="transparent" color="green" size="xs">
                      <IconCheck size={12} />
                    </ThemeIcon>
                    <Text size="sm">{line}</Text>
                  </Group>
                ))}
              </Stack>
            </Stack>
          </Card>
        </SimpleGrid>

        <Card withBorder padding="lg" radius="md" mt="lg">
          <Stack gap="sm">
            <Text size="sm" fw={600}>
              {t('mcp.configHeading')}
            </Text>
            <Text size="xs" c="dimmed">
              {t('mcp.configHint')}
            </Text>
            <Code
              block
              style={{ fontSize: 12, padding: 12, background: 'var(--mantine-color-dark-7)' }}
            >
              {configSnippet}
            </Code>
            <Group gap="md">
              <Anchor
                href="https://www.npmjs.com/package/@arguslog/mcp-server"
                target="_blank"
                rel="noopener noreferrer"
                size="sm"
              >
                {t('mcp.linkNpm')}
              </Anchor>
              <Anchor
                href={`${GITHUB_URL}/blob/main/packages/mcp-server/README.md`}
                target="_blank"
                rel="noopener noreferrer"
                size="sm"
              >
                {t('mcp.linkDocs')}
              </Anchor>
            </Group>
          </Stack>
        </Card>
      </Container>
    </Box>
  );
}


function FooterSection({ dashboardUrl }: { dashboardUrl: string }) {
  const { t } = useTranslation();
  return (
    <Box py={48}>
      <Container size="lg">
        <Divider mb="lg" />
        <Group justify="space-between" wrap="wrap">
          <Group gap="sm">
            <img src="/arguslog.svg" alt="" width={20} height={20} />
            <Text size="sm" c="dimmed">
              {t('footer.tagline')}
            </Text>
          </Group>
          <Group gap="lg">
            <Anchor href={dashboardUrl} size="sm" c="dimmed">
              {t('footer.links.app')}
            </Anchor>
            <Anchor
              href={GITHUB_URL}
              target="_blank"
              rel="noopener noreferrer"
              size="sm"
              c="dimmed"
            >
              {t('footer.links.github')}
            </Anchor>
            <Text size="sm" c="dimmed">
              {t('footer.copyright')}
            </Text>
          </Group>
        </Group>
      </Container>
    </Box>
  );
}
