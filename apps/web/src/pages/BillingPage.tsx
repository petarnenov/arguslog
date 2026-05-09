import {
  Alert,
  Badge,
  Box,
  Button,
  Card,
  Group,
  List,
  Loader,
  Progress,
  SegmentedControl,
  SimpleGrid,
  Stack,
  Text,
  ThemeIcon,
  Title,
} from '@mantine/core';
import { IconCheck } from '@tabler/icons-react';
import { useMutation } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useParams } from 'react-router';

import {
  startCryptoCheckout,
  type DurationOffer,
  type PaidTier,
  type PlanDuration,
  type PlanTierInfo,
} from '../api/billing';
import { ApiError } from '../api/client';
import { useBillingPlans, useMyOrgs, useUsage } from '../api/queries';
import { useReportSoftError } from '../lib/reportSoftError';

const TIER_NAME_KEY: Record<string, string> = {
  free: 'billing.tierFree',
  starter: 'billing.tierStarter',
  pro: 'billing.tierPro',
  business: 'billing.tierBusiness',
};

const TIER_TAGLINE_KEY: Record<string, string> = {
  free: 'billing.tierFreeTagline',
  starter: 'billing.tierStarterTagline',
  pro: 'billing.tierProTagline',
  business: 'billing.tierBusinessTagline',
};

const PAID_TIERS: PaidTier[] = ['starter', 'pro', 'business'];

function isPaidTier(plan: string): plan is PaidTier {
  return (PAID_TIERS as string[]).includes(plan);
}

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

  const [selectedTier, setSelectedTier] = useState<string | null>(null);
  const [selectedDuration, setSelectedDuration] = useState<PlanDuration>(12);

  const cryptoCheckout = useMutation({
    mutationFn: ({ tier, duration }: { tier: PaidTier; duration: PlanDuration }) =>
      startCryptoCheckout(org!.id, tier, duration),
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
  if (usage.isError || !usage.data || !plans.data) {
    return (
      <Alert color="red" variant="light">
        {t('errors.generic')}
      </Alert>
    );
  }

  const snapshot = usage.data;
  const percent = Math.min(100, Math.round(snapshot.ratio * 100));
  const progressColor = snapshot.exceeded ? 'red' : percent >= 80 ? 'yellow' : 'teal';
  const renewsLabel =
    snapshot.plan !== 'free' && snapshot.renewsAt ? formatRenewalDate(snapshot.renewsAt) : null;

  const checkoutError = errorMessage(cryptoCheckout.error);
  const graceDaysRemaining = snapshot.paymentGraceUntil
    ? daysUntil(snapshot.paymentGraceUntil)
    : null;
  const renewDaysRemaining =
    snapshot.plan !== 'free' && snapshot.renewsAt ? daysUntil(snapshot.renewsAt) : null;
  const renewSoon =
    renewDaysRemaining !== null && renewDaysRemaining > 0 && renewDaysRemaining <= 14;
  const renewExpired = renewDaysRemaining === 0;

  return (
    <Stack maw={1200}>
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

      <Title order={4}>{t('billing.pickPlan')}</Title>
      <SimpleGrid cols={{ base: 1, sm: 2, md: 4 }} spacing="md">
        {plans.data.tiers.map((tier) => (
          <TierCard
            key={tier.plan}
            tier={tier}
            isCurrent={tier.plan === snapshot.plan}
            isSelected={selectedTier === tier.plan}
            onSelect={() => setSelectedTier(tier.plan)}
            selectedDuration={selectedDuration}
            onSelectDuration={setSelectedDuration}
            paying={
              cryptoCheckout.isPending && cryptoCheckout.variables?.tier === tier.plan
            }
            onPay={() => {
              if (!isPaidTier(tier.plan)) return;
              cryptoCheckout.mutate({ tier: tier.plan, duration: selectedDuration });
            }}
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
  );
}

function TierCard({
  tier,
  isCurrent,
  isSelected,
  onSelect,
  selectedDuration,
  onSelectDuration,
  paying,
  onPay,
}: {
  tier: PlanTierInfo;
  isCurrent: boolean;
  isSelected: boolean;
  onSelect: () => void;
  selectedDuration: PlanDuration;
  onSelectDuration: (d: PlanDuration) => void;
  paying: boolean;
  onPay: () => void;
}) {
  const { t } = useTranslation();
  const isPaid = isPaidTier(tier.plan);
  const isPopular = tier.plan === 'pro';
  const offer = tier.durations.find((d) => d.months === selectedDuration);

  const accentBorder = isSelected
    ? 'var(--mantine-color-blue-6)'
    : isPopular
      ? 'var(--mantine-color-blue-3)'
      : undefined;

  return (
    <Card
      withBorder
      padding="lg"
      radius="md"
      data-testid={`tier-card-${tier.plan}`}
      data-selected={isSelected}
      onClick={onSelect}
      style={{
        cursor: 'pointer',
        position: 'relative',
        borderColor: accentBorder,
        borderWidth: isSelected ? 2 : 1,
      }}
    >
      {isPopular && !isSelected && (
        <Badge
          variant="light"
          color="blue"
          size="sm"
          style={{ position: 'absolute', top: -10, right: 12 }}
        >
          {t('billing.popularBadge')}
        </Badge>
      )}
      {isCurrent && (
        <Badge
          variant="filled"
          color="teal"
          size="sm"
          style={{ position: 'absolute', top: -10, left: 12 }}
        >
          {t('billing.tierStarterCurrent')}
        </Badge>
      )}

      <Stack gap="sm">
        <Stack gap={2}>
          <Text fw={700} size="lg">
            {t(TIER_NAME_KEY[tier.plan] ?? 'billing.tierFree')}
          </Text>
          <Text size="xs" c="dimmed">
            {t(TIER_TAGLINE_KEY[tier.plan] ?? 'billing.tierFreeTagline')}
          </Text>
        </Stack>

        <Box>
          <Text size="xl" fw={700}>
            {isPaid
              ? t('billing.perMonthFromCents', {
                  price: formatDollars(tier.monthlyPriceCents),
                })
              : t('billing.freeTierPrice')}
          </Text>
        </Box>

        <List
          spacing={4}
          size="sm"
          icon={
            <ThemeIcon color="teal" size={18} radius="xl">
              <IconCheck size={12} stroke={3} />
            </ThemeIcon>
          }
        >
          <List.Item>
            {tier.unlimitedEvents
              ? t('billing.tierEventsUnlimited')
              : t('billing.tierEventsLine', { events: formatNumber(tier.monthlyEventCap) })}
          </List.Item>
          <List.Item>
            {tier.unlimitedProjects
              ? t('billing.tierProjectsUnlimited')
              : t('billing.tierProjectsLine', { count: tier.projectCap })}
          </List.Item>
          <List.Item>
            {tier.unlimitedMembers
              ? t('billing.tierMembersUnlimited')
              : t('billing.tierMembersLine', { count: tier.memberCap })}
          </List.Item>
          <List.Item>
            {t('billing.tierRetentionLine', { days: tier.retentionDays })}
          </List.Item>
          <List.Item>{t('billing.tierAlertsLine')}</List.Item>
        </List>

        {isPaid && isSelected && (
          <Stack gap="xs" pt="sm" data-testid={`tier-card-${tier.plan}-expanded`}>
            <Text size="xs" c="dimmed" fw={600} tt="uppercase">
              {t('billing.selectDuration')}
            </Text>
            <SegmentedControl
              fullWidth
              size="xs"
              value={String(selectedDuration)}
              onChange={(v) => onSelectDuration(Number(v) as PlanDuration)}
              data={tier.durations.map((d) => ({
                label: durationLabel(d),
                value: String(d.months),
              }))}
            />
            <Button
              fullWidth
              loading={paying}
              onClick={(e) => {
                e.stopPropagation();
                onPay();
              }}
              data-testid={`pay-${tier.plan}-${selectedDuration}`}
            >
              {t('billing.payTotalButton', {
                total: formatDollars(offer?.amountCents ?? 0),
              })}
            </Button>
          </Stack>
        )}

        {isPaid && !isSelected && (
          <Button
            fullWidth
            variant="light"
            onClick={(e) => {
              e.stopPropagation();
              onSelect();
            }}
            data-testid={`select-${tier.plan}`}
          >
            {t('billing.tierSelectButton')}
          </Button>
        )}
      </Stack>
    </Card>
  );
}

function durationLabel(offer: DurationOffer): string {
  if (offer.savePercent === 0) return `${offer.months}mo`;
  return `${offer.months}mo (-${offer.savePercent}%)`;
}

function errorMessage(err: unknown): string | null {
  if (!err) return null;
  if (err instanceof ApiError) return err.problem.detail ?? err.problem.title;
  return String(err);
}

function formatNumber(n: number): string {
  if (n >= Number.MAX_SAFE_INTEGER) return '∞';
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
