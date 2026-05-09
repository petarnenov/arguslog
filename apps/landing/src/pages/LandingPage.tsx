import {
  Anchor,
  AppShell,
  Badge,
  Box,
  Button,
  Card,
  Center,
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
  IconRoute,
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
        <Pricing onboardingUrl={onboardingUrl} />
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

interface PricingTier {
  key: 'free' | 'starter' | 'pro' | 'business';
  highlight?: boolean;
}

const TIERS: PricingTier[] = [
  { key: 'free' },
  { key: 'starter' },
  { key: 'pro', highlight: true },
  { key: 'business' },
];

function Pricing({ onboardingUrl }: { onboardingUrl: string }) {
  const { t } = useTranslation();

  return (
    <Box py={64} bg="var(--mantine-color-body)">
      <Container size="lg">
        <Stack gap="xs" mb="xl">
          <Title order={2}>{t('pricing.heading')}</Title>
          <Text c="dimmed" maw={760}>
            {t('pricing.subheading')}
          </Text>
        </Stack>
        <SimpleGrid cols={{ base: 1, sm: 2, md: 4 }} spacing="lg">
          {TIERS.map((tier) => {
            const features = t(`pricing.${tier.key}.features`, { returnObjects: true }) as string[];
            return (
              <Card
                key={tier.key}
                withBorder
                padding="lg"
                radius="md"
                data-testid={`pricing-tier-${tier.key}`}
                style={
                  tier.highlight
                    ? { borderColor: 'var(--mantine-color-green-6)', borderWidth: 2 }
                    : undefined
                }
              >
                <Stack gap="md" h="100%">
                  <Stack gap={4}>
                    <Group justify="space-between" align="flex-start">
                      <Title order={3}>{t(`pricing.${tier.key}.name`)}</Title>
                      {tier.highlight ? (
                        <Badge color="green" variant="light">
                          {t('pricing.popularBadge')}
                        </Badge>
                      ) : null}
                    </Group>
                    <Text size="sm" c="dimmed">
                      {t(`pricing.${tier.key}.tagline`)}
                    </Text>
                  </Stack>
                  <Group gap={4} align="baseline">
                    <Title order={2}>{t(`pricing.${tier.key}.price`)}</Title>
                    <Text c="dimmed" size="sm">
                      {t(`pricing.${tier.key}.period`)}
                    </Text>
                  </Group>
                  <Stack gap="xs" style={{ flex: 1 }}>
                    {features.map((f) => (
                      <Group key={f} gap="xs" wrap="nowrap" align="flex-start">
                        <ThemeIcon variant="light" size="sm" radius="xl" color="green" mt={2}>
                          <IconCheck size={12} />
                        </ThemeIcon>
                        <Text size="sm">{f}</Text>
                      </Group>
                    ))}
                  </Stack>
                  <Button
                    component="a"
                    href={onboardingUrl}
                    variant={tier.highlight ? 'filled' : 'default'}
                    fullWidth
                  >
                    {t(`pricing.${tier.key}.cta`)}
                  </Button>
                </Stack>
              </Card>
            );
          })}
        </SimpleGrid>

        <Card withBorder padding="lg" radius="md" mt="lg" data-testid="pricing-tier-enterprise">
          <Group justify="space-between" wrap="wrap" gap="md">
            <Stack gap={4} style={{ flex: 1, minWidth: 280 }}>
              <Group gap="sm" align="baseline">
                <Title order={3}>{t('pricing.enterprise.name')}</Title>
                <Text c="dimmed" size="sm">
                  {t('pricing.enterprise.tagline')}
                </Text>
              </Group>
              <Text c="dimmed" size="sm">
                {t('pricing.enterprise.description')}
              </Text>
            </Stack>
            <Button
              component="a"
              href={`mailto:hello@arguslog.org?subject=${encodeURIComponent('Enterprise plan')}`}
              variant="default"
            >
              {t('pricing.enterprise.cta')}
            </Button>
          </Group>
        </Card>

        <Text c="dimmed" size="sm" ta="center" mt="lg" maw={720} mx="auto">
          {t('pricing.annualNote')}
        </Text>
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
