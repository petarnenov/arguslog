import {
  Alert,
  Badge,
  Button,
  Card,
  Group,
  Loader,
  Progress,
  SegmentedControl,
  Stack,
  Text,
  Title,
} from '@mantine/core';
import { useMutation } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useParams } from 'react-router';

import { openPortal, startCheckout, type BillingInterval } from '../api/billing';
import { ApiError } from '../api/client';
import { useMyOrgs, useUsage } from '../api/queries';

export function BillingPage() {
  const { t } = useTranslation();
  const { orgSlug } = useParams();
  const orgs = useMyOrgs();
  const org = orgs.data?.find((o) => o.slug === orgSlug);
  const usage = useUsage(org?.id);

  // The picker is only visible BEFORE checkout (free → pro). Once subscribed, switching
  // monthly↔annual goes through the Stripe Customer Portal so we don't have to write proration
  // logic ourselves; the webhook informs us of the new cadence.
  const [interval, setInterval] = useState<BillingInterval>('monthly');

  const checkout = useMutation({
    mutationFn: () => startCheckout(org!.id, interval),
    onSuccess: ({ url }) => {
      window.location.assign(url);
    },
  });
  const portal = useMutation({
    mutationFn: () => openPortal(org!.id),
    onSuccess: ({ url }) => {
      window.location.assign(url);
    },
  });

  if (orgs.isLoading || usage.isLoading) {
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
  const priceLabel = formatPriceLabel(snapshot.monthlyPriceCents, snapshot.billingInterval, isPro);
  const renewsLabel = isPro && snapshot.renewsAt ? formatRenewalDate(snapshot.renewsAt) : null;

  const checkoutError = errorMessage(checkout.error);
  const portalError = errorMessage(portal.error);
  const graceDaysRemaining = snapshot.paymentGraceUntil
    ? daysUntil(snapshot.paymentGraceUntil)
    : null;

  return (
    <Stack maw={760}>
      <Title order={3}>{t('billing.title')}</Title>

      {graceDaysRemaining !== null && (
        <Alert color="red" variant="filled" data-testid="payment-grace-banner">
          <Group justify="space-between" align="center" wrap="nowrap">
            <Stack gap={2}>
              <Text fw={600}>{t('billing.paymentFailedTitle')}</Text>
              <Text size="sm">{t('billing.paymentFailedBody', { days: graceDaysRemaining })}</Text>
            </Stack>
            <Button
              variant="white"
              color="red"
              loading={portal.isPending}
              onClick={() => portal.mutate()}
              data-testid="update-payment-button"
            >
              {t('billing.updatePayment')}
            </Button>
          </Group>
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
                {priceLabel && (
                  <Badge variant="light" color="blue">
                    {priceLabel}
                  </Badge>
                )}
              </Group>
              {renewsLabel && (
                <Text size="xs" c="dimmed" data-testid="renews-at">
                  {t('billing.renewsOn', { date: renewsLabel })}
                </Text>
              )}
            </Stack>
            <Group gap="xs">
              {!isPro && (
                <Stack gap="xs" align="flex-end">
                  <SegmentedControl
                    value={interval}
                    onChange={(v) => setInterval(v as BillingInterval)}
                    data-testid="billing-interval-toggle"
                    data={[
                      { label: t('billing.intervalMonthly'), value: 'monthly' },
                      { label: t('billing.intervalAnnualSave'), value: 'annual' },
                    ]}
                  />
                  <Button
                    loading={checkout.isPending}
                    onClick={() => checkout.mutate()}
                    data-testid="upgrade-button"
                  >
                    {t('billing.upgrade')}
                  </Button>
                </Stack>
              )}
              {isPro && (
                <Button
                  variant="default"
                  loading={portal.isPending}
                  onClick={() => portal.mutate()}
                  data-testid="manage-button"
                >
                  {t('billing.manage')}
                </Button>
              )}
            </Group>
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

          {checkoutError && (
            <Alert color="red" variant="light">
              {checkoutError}
            </Alert>
          )}
          {portalError && (
            <Alert color="red" variant="light">
              {portalError}
            </Alert>
          )}
        </Stack>
      </Card>
    </Stack>
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

function daysUntil(iso: string): number {
  const ms = new Date(iso).getTime() - Date.now();
  // Math.ceil so the banner reads "1 day remaining" until the deadline actually passes,
  // and clamps to 0 once expired (worker hasn't run the downgrade yet).
  return Math.max(0, Math.ceil(ms / (24 * 3600 * 1000)));
}

function formatPriceLabel(
  monthlyPriceCents: number,
  interval: BillingInterval,
  isPro: boolean,
): string | null {
  if (monthlyPriceCents <= 0) return null;
  // For an active Pro subscriber, surface their actual cadence ($9/mo or $90/yr). For a free-tier
  // viewer, the badge is a teaser of the monthly Pro price — annual pricing is reserved for the
  // segmented-control row beside the upgrade button.
  if (isPro && interval === 'annual') {
    const annual = (monthlyPriceCents * 10) / 100; // ~10× monthly = 17% off 12×, rounded to a whole dollar
    return `$${annual.toFixed(0)}/yr`;
  }
  return `$${(monthlyPriceCents / 100).toFixed(0)}/mo`;
}

function formatRenewalDate(iso: string): string {
  return new Date(iso).toLocaleDateString();
}
