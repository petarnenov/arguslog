import {
  Alert,
  Badge,
  Box,
  Button,
  Card,
  Group,
  Loader,
  Progress,
  SimpleGrid,
  Stack,
  Text,
  Title,
} from '@mantine/core';
import { useMutation } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { useParams } from 'react-router';

import { startCryptoCheckout, type DurationOffer, type PlanDuration } from '../api/billing';
import { ApiError } from '../api/client';
import { useBillingPlans, useMyOrgs, useUsage } from '../api/queries';
import { useReportSoftError } from '../lib/reportSoftError';

const DURATION_LABEL_KEY: Record<PlanDuration, string> = {
  1: 'billing.duration1',
  3: 'billing.duration3',
  6: 'billing.duration6',
  12: 'billing.duration12',
};

export function BillingPage() {
  const { t } = useTranslation();
  const { orgSlug } = useParams();
  const orgs = useMyOrgs();
  const org = orgs.data?.find((o) => o.slug === orgSlug);
  const usage = useUsage(org?.id);
  const plans = useBillingPlans();

  useReportSoftError(
    Boolean(orgs.data && !org && orgSlug),
    `BillingPage: org slug "${orgSlug}" not in user's memberships ` +
      `(known: ${(orgs.data ?? []).map((o) => o.slug).join(',') || 'none'})`,
  );

  const cryptoCheckout = useMutation({
    mutationFn: ({ duration }: { duration: PlanDuration }) =>
      startCryptoCheckout(org!.id, duration),
    onSuccess: ({ checkoutUrl }) => {
      window.location.assign(checkoutUrl);
    },
  });

  if (orgs.isLoading || usage.isLoading || plans.isLoading) {
    return (
      <Group p="md">
        <Loader size="sm" />
      </Group>
    );
  }
  if (!org) {
    return (
      <Alert color="red" variant="light">
        {t('projects.orgNotFound')}
      </Alert>
    );
  }
  if (usage.isError || !usage.data) {
    return (
      <Alert color="red" variant="light">
        {t('errors.generic')}
      </Alert>
    );
  }

  const snapshot = usage.data;
  const isPro = snapshot.plan === 'pro';
  const percent = Math.min(100, Math.round(snapshot.ratio * 100));
  const progressColor = snapshot.exceeded ? 'red' : percent >= 80 ? 'yellow' : 'teal';
  const renewsLabel = isPro && snapshot.renewsAt ? formatRenewalDate(snapshot.renewsAt) : null;

  const checkoutError = errorMessage(cryptoCheckout.error);
  const graceDaysRemaining = snapshot.paymentGraceUntil
    ? daysUntil(snapshot.paymentGraceUntil)
    : null;
  const renewDaysRemaining =
    isPro && snapshot.renewsAt ? daysUntil(snapshot.renewsAt) : null;
  const renewSoon =
    renewDaysRemaining !== null && renewDaysRemaining > 0 && renewDaysRemaining <= 14;
  const renewExpired = renewDaysRemaining === 0;

  const proOffers: DurationOffer[] = plans.data?.pro.durations ?? [];
  const showCheckout = !isPro || renewSoon || renewExpired;

  return (
    <Stack maw={1040}>
      <Title order={3}>{t('billing.title')}</Title>

      {graceDaysRemaining !== null && (
        <Alert color="red" variant="filled" data-testid="payment-grace-banner">
          <Group justify="space-between" align="center" wrap="nowrap">
            <Stack gap={2}>
              <Text fw={600}>{t('billing.paymentFailedTitle')}</Text>
              <Text size="sm">{t('billing.paymentFailedBody', { days: graceDaysRemaining })}</Text>
            </Stack>
          </Group>
        </Alert>
      )}

      {(renewSoon || renewExpired) && (
        <Alert
          color={renewExpired ? 'red' : 'yellow'}
          variant="light"
          data-testid="renew-banner"
        >
          <Text size="sm" fw={500}>
            {renewExpired
              ? t('billing.renewExpired')
              : t('billing.renewSoon', { days: renewDaysRemaining })}
          </Text>
        </Alert>
      )}

      <Card withBorder padding="lg" radius="md">
        <Stack gap="md">
          <Group justify="space-between" align="flex-start">
            <Stack gap={2}>
              <Text size="xs" c="dimmed" tt="uppercase" fw={600}>
                {t('billing.currentPlan')}
              </Text>
              <Group gap="xs" align="center">
                <Title order={2} tt="capitalize">
                  {snapshot.plan}
                </Title>
                {!isPro && (
                  <Badge variant="light" color="gray">
                    {t('billing.freeBadge')}
                  </Badge>
                )}
              </Group>
              {renewsLabel && (
                <Text size="xs" c="dimmed" data-testid="renews-at">
                  {t('billing.renewsOn', { date: renewsLabel })}
                </Text>
              )}
            </Stack>
          </Group>

          <Stack gap={4}>
            <Group justify="space-between">
              <Text size="sm" fw={500}>
                {t('billing.eventsThisMonth')}
              </Text>
              <Text size="sm" c="dimmed" data-testid="usage-ratio">
                {formatNumber(snapshot.eventsUsed)} / {formatNumber(snapshot.eventCap)}
              </Text>
            </Group>
            <Progress value={percent} color={progressColor} size="lg" radius="sm" />
            {snapshot.exceeded && (
              <Text size="xs" c="red.7" fw={500}>
                {t('billing.capExceeded')}
              </Text>
            )}
          </Stack>

          <Group gap="xl">
            <Stack gap={2}>
              <Text size="xs" c="dimmed" tt="uppercase" fw={600}>
                {t('billing.projectCap')}
              </Text>
              <Text size="sm">{snapshot.projectCap}</Text>
            </Stack>
            <Stack gap={2}>
              <Text size="xs" c="dimmed" tt="uppercase" fw={600}>
                {t('billing.retention')}
              </Text>
              <Text size="sm">{t('billing.retentionDays', { days: snapshot.retentionDays })}</Text>
            </Stack>
          </Group>
        </Stack>
      </Card>

      {showCheckout && proOffers.length > 0 && (
        <Stack gap="sm">
          <Title order={4}>{isPro ? t('billing.extendPlan') : t('billing.pickPlan')}</Title>
          <SimpleGrid cols={{ base: 1, sm: 2, md: 4 }} spacing="md">
            {proOffers.map((offer) => (
              <DurationCard
                key={offer.months}
                offer={offer}
                pending={
                  cryptoCheckout.isPending &&
                  cryptoCheckout.variables?.duration === offer.months
                }
                onPick={() => cryptoCheckout.mutate({ duration: offer.months })}
              />
            ))}
          </SimpleGrid>
          <Text size="xs" c="dimmed">
            {t('billing.cryptoNote')}
          </Text>
          {checkoutError && (
            <Alert color="red" variant="light">
              {checkoutError}
            </Alert>
          )}
        </Stack>
      )}
    </Stack>
  );
}

function DurationCard({
  offer,
  pending,
  onPick,
}: {
  offer: DurationOffer;
  pending: boolean;
  onPick: () => void;
}) {
  const { t } = useTranslation();
  const isHighlighted = offer.months === 12;

  return (
    <Card
      withBorder
      padding="lg"
      radius="md"
      data-testid={`duration-card-${offer.months}`}
      style={
        isHighlighted
          ? {
              borderColor: 'var(--mantine-color-blue-6)',
              borderWidth: 2,
              position: 'relative',
            }
          : { position: 'relative' }
      }
    >
      {isHighlighted && (
        <Badge
          variant="filled"
          color="blue"
          size="sm"
          style={{ position: 'absolute', top: -10, right: 12 }}
        >
          {t('billing.bestValue')}
        </Badge>
      )}
      <Stack gap="xs">
        <Group justify="space-between" align="center">
          <Text fw={600}>{t(DURATION_LABEL_KEY[offer.months])}</Text>
          {offer.savePercent > 0 && (
            <Badge variant="light" color="green" size="sm">
              {t('billing.saveBadge', { percent: offer.savePercent })}
            </Badge>
          )}
        </Group>
        <Box>
          <Text size="xl" fw={700}>
            {t('billing.totalPrice', { price: formatDollars(offer.amountCents) })}
          </Text>
          <Text size="xs" c="dimmed">
            {t('billing.perMonth', { price: formatDollars(offer.perMonthCents) })}
          </Text>
        </Box>
        <Button
          fullWidth
          variant={isHighlighted ? 'filled' : 'light'}
          loading={pending}
          onClick={onPick}
          data-testid={`pay-crypto-${offer.months}`}
        >
          {t('billing.payWithCrypto')}
        </Button>
      </Stack>
    </Card>
  );
}

function errorMessage(err: unknown): string | null {
  if (!err) return null;
  if (err instanceof ApiError) return err.problem.detail ?? err.problem.title;
  return String(err);
}

function formatNumber(n: number): string {
  return new Intl.NumberFormat('en-US').format(n);
}

function formatDollars(cents: number): string {
  return (cents / 100).toFixed(2);
}

function daysUntil(iso: string): number {
  const ms = new Date(iso).getTime() - Date.now();
  return Math.max(0, Math.ceil(ms / (24 * 3600 * 1000)));
}

function formatRenewalDate(iso: string): string {
  return new Date(iso).toLocaleDateString();
}
