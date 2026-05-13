import {
  Alert,
  AppShell,
  Badge,
  Box,
  Card,
  Center,
  Container,
  Group,
  Loader,
  Stack,
  Text,
  ThemeIcon,
  Title,
} from '@mantine/core';
import { IconActivity, IconAlertTriangle, IconCheck, IconHistory, IconX } from '@tabler/icons-react';
import { useQuery } from '@tanstack/react-query';
import { useEffect, useMemo } from 'react';
import { useTranslation } from 'react-i18next';

import rawIncidents from '../data/incidents.md?raw';
import {
  overallStatus,
  probeAll,
  SERVICES,
  type OverallStatus,
  type ProbeResult,
  type ServiceStatus,
} from '../lib/healthChecks';
import { parseIncidents, type IncidentSeverity, type IncidentStatus } from '../lib/incidents';

const POLL_MS = 30_000;

const OVERALL_PRESENT: Record<
  OverallStatus,
  { color: string; icon: typeof IconCheck; labelKey: string }
> = {
  operational: { color: 'green', icon: IconCheck, labelKey: 'status.overall.operational' },
  degraded: { color: 'yellow', icon: IconAlertTriangle, labelKey: 'status.overall.degraded' },
  outage: { color: 'red', icon: IconX, labelKey: 'status.overall.outage' },
  unknown: { color: 'gray', icon: IconActivity, labelKey: 'status.overall.unknown' },
};

const SERVICE_COLOR: Record<ServiceStatus, string> = {
  up: 'green',
  down: 'red',
  unknown: 'gray',
};

const SEVERITY_COLOR: Record<IncidentSeverity, string> = {
  minor: 'yellow',
  major: 'orange',
  critical: 'red',
  unknown: 'gray',
};

const STATUS_COLOR: Record<IncidentStatus, string> = {
  investigating: 'orange',
  identified: 'yellow',
  monitoring: 'blue',
  resolved: 'green',
  unknown: 'gray',
};

export function StatusPage() {
  const { t, i18n } = useTranslation();

  // useQuery handles the polling timer + abort on unmount without us managing setInterval.
  const probe = useQuery({
    queryKey: ['status', 'probe'],
    queryFn: ({ signal }) => probeAll(signal),
    refetchInterval: POLL_MS,
    refetchIntervalInBackground: false,
    staleTime: POLL_MS - 5_000,
  });

  // Document title updates so a tab-switcher quickly sees aggregate state.
  useEffect(() => {
    if (!probe.data) return;
    const overall = overallStatus(probe.data);
    const labels = {
      operational: '✓ All systems operational',
      degraded: '⚠ Degraded performance',
      outage: '✗ Outage',
      unknown: 'Checking…',
    };
    document.title = `${labels[overall]} — Arguslog`;
    return () => {
      document.title = 'Arguslog';
    };
  }, [probe.data]);

  const incidents = useMemo(() => parseIncidents(rawIncidents as string), []);

  const overall = probe.data ? overallStatus(probe.data) : 'unknown';
  const present = OVERALL_PRESENT[overall];
  const OverallIcon = present.icon;
  const lastCheckedAt = probe.data?.[0]?.checkedAt;

  const formatter = useMemo(
    () =>
      new Intl.DateTimeFormat(i18n.language || 'en', {
        dateStyle: 'medium',
        timeStyle: 'short',
      }),
    [i18n.language],
  );

  return (
    <AppShell padding={0}>
      <AppShell.Main>
        <Container size="lg" py="xl">
          <Stack gap="xl">
            <Stack gap="xs">
              <Title order={1}>{t('status.title')}</Title>
              <Text c="dimmed">{t('status.subtitle')}</Text>
            </Stack>

            {/* Overall plaque */}
            <Card withBorder padding="xl" radius="md" data-testid="status-overall">
              <Group gap="md" align="center" wrap="nowrap">
                <ThemeIcon size={56} radius="xl" color={present.color} variant="light">
                  <OverallIcon size={32} />
                </ThemeIcon>
                <Stack gap={2} style={{ flex: 1 }}>
                  <Title order={2}>{t(present.labelKey)}</Title>
                  {lastCheckedAt ? (
                    <Text size="sm" c="dimmed">
                      {t('status.lastChecked', { when: formatter.format(new Date(lastCheckedAt)) })}
                    </Text>
                  ) : (
                    <Text size="sm" c="dimmed">
                      {t('status.checking')}
                    </Text>
                  )}
                </Stack>
                {probe.isFetching && <Loader size="sm" />}
              </Group>
            </Card>

            {/* Per-service tiles */}
            <Stack gap="sm">
              <Title order={3}>{t('status.servicesTitle')}</Title>
              {probe.isLoading && !probe.data ? (
                <Center py="xl">
                  <Loader />
                </Center>
              ) : (
                <Stack gap="xs">
                  {SERVICES.map((s) => {
                    const result =
                      probe.data?.find((r) => r.id === s.id) ?? {
                        id: s.id,
                        status: 'unknown' as ServiceStatus,
                        latencyMs: null,
                        checkedAt: new Date().toISOString(),
                      };
                    return (
                      <ServiceTile key={s.id} name={s.name} description={s.description} result={result} />
                    );
                  })}
                </Stack>
              )}
              <Alert color="gray" variant="light" icon={<IconActivity size={16} />}>
                {t('status.workerNote')}
              </Alert>
            </Stack>

            {/* Incident history */}
            <Stack gap="sm">
              <Group gap="sm" align="center">
                <IconHistory size={20} />
                <Title order={3}>{t('status.incidentsTitle')}</Title>
              </Group>
              {incidents.length === 0 ? (
                <Text c="dimmed">{t('status.incidentsEmpty')}</Text>
              ) : (
                <Stack gap="xs">
                  {incidents.slice(0, 10).map((inc) => (
                    <Card key={inc.startedAt + inc.title} withBorder padding="md" radius="md">
                      <Stack gap={4}>
                        <Group gap="sm" wrap="wrap">
                          <Badge color={SEVERITY_COLOR[inc.severity]} variant="light">
                            {t(`status.severity.${inc.severity}`)}
                          </Badge>
                          <Badge color={STATUS_COLOR[inc.status]} variant="outline">
                            {t(`status.incidentStatus.${inc.status}`)}
                          </Badge>
                          <Text fw={500}>{inc.title}</Text>
                        </Group>
                        <Text size="xs" c="dimmed">
                          {formatter.format(new Date(inc.startedAt))}
                          {inc.duration ? ` · ${inc.duration}` : ''}
                          {inc.affected.length > 0 ? ` · ${inc.affected.join(', ')}` : ''}
                        </Text>
                        {inc.description && (
                          <Text size="sm" c="dimmed">
                            {inc.description}
                          </Text>
                        )}
                      </Stack>
                    </Card>
                  ))}
                </Stack>
              )}
            </Stack>
          </Stack>
        </Container>
      </AppShell.Main>
    </AppShell>
  );
}

interface TileProps {
  name: string;
  description: string;
  result: ProbeResult;
}

function ServiceTile({ name, description, result }: TileProps) {
  const { t } = useTranslation();
  const color = SERVICE_COLOR[result.status];
  return (
    <Card withBorder padding="md" radius="md" data-testid={`status-service-${result.id}`}>
      <Group gap="sm" wrap="nowrap">
        <Box
          aria-hidden
          style={{
            width: 12,
            height: 12,
            borderRadius: '50%',
            backgroundColor: `var(--mantine-color-${color}-6)`,
            flexShrink: 0,
          }}
        />
        <Stack gap={0} style={{ flex: 1 }}>
          <Group gap="xs" align="center" wrap="wrap">
            <Text fw={500}>{name}</Text>
            <Badge size="xs" color={color} variant="light">
              {t(`status.serviceStatus.${result.status}`)}
            </Badge>
          </Group>
          <Text size="xs" c="dimmed">
            {description}
          </Text>
          {result.error && (
            <Text size="xs" c="red.6">
              {result.error}
            </Text>
          )}
        </Stack>
        {result.latencyMs != null && (
          <Text size="xs" c="dimmed">
            {result.latencyMs}ms
          </Text>
        )}
      </Group>
    </Card>
  );
}
