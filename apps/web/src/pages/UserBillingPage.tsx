import {
  Alert,
  Badge,
  Box,
  Button,
  Card,
  Group,
  List,
  Loader,
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

import {
  openMePortal,
  startMeCheckout,
  startMeCryptoCheckout,
  type PaidTier,
  type PlanDuration,
  type PlanTierInfo,
} from '../api/billing';
import { ApiError } from '../api/client';
import { useBillingPlans, useMe } from '../api/queries';
import { BonusBanner } from '../components/BonusBanner';

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

/**
 * User-level Billing page (V27+). Reads plan / renew / bonus / grace from /me and routes
 * checkout + portal + crypto through /me/billing — the backend resolves the user's primary
 * owned org under the hood. Per-user is the source of truth post-V27; this page is the only
 * place a user pays from.
 */
export function UserBillingPage() {
  const { t } = useTranslation();
  const me = useMe();
  const plans = useBillingPlans();

  const [selectedTier, setSelectedTier] = useState<string | null>(null);
  const [selectedDuration, setSelectedDuration] = useState<PlanDuration>(12);

  const cardCheckout = useMutation({
    mutationFn: () => startMeCheckout('monthly'),
    onSuccess: ({ url }) => {
      window.location.assign(url);
    },
  });

  const portal = useMutation({
    mutationFn: () => openMePortal(),
    onSuccess: ({ url }) => {
      window.location.assign(url);
    },
  });

  const cryptoCheckout = useMutation({
    mutationFn: ({ tier, duration }: { tier: PaidTier; duration: PlanDuration }) =>
      startMeCryptoCheckout(tier, duration),
    onSuccess: ({ checkoutUrl }) => {
      window.location.assign(checkoutUrl);
    },
  });

  if (me.isLoading || plans.isLoading) {
    return (
      <Group p="md">
        <Loader size="sm" />
      </Group>
    );
  }
  if (me.isError || !me.data || plans.isError || !plans.data) {
    return (
      <Alert color="red" variant="light">
        {t('errors.generic')}
      </Alert>
    );
  }

  const data = me.data;
  const isPaid = isPaidTier(data.plan);
  const renewsLabel = data.planRenewsAt ? formatRenewalDate(data.planRenewsAt) : null;
  const graceDaysRemaining = data.paymentGraceUntil ? daysUntil(data.paymentGraceUntil) : null;
  const renewDaysRemaining = data.planRenewsAt ? daysUntil(data.planRenewsAt) : null;
  const renewSoon =
    renewDaysRemaining !== null && renewDaysRemaining > 0 && renewDaysRemaining <= 14;
  const renewExpired = renewDaysRemaining === 0;
  const checkoutError =
    errorMessage(cardCheckout.error) ??
    errorMessage(cryptoCheckout.error) ??
    errorMessage(portal.error);

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
        <Alert color={renewExpired ? 'red' : 'yellow'} variant="light" data-testid="renew-banner">
          <Text size="sm" fw={500}>
            {renewExpired
              ? t('billing.renewExpired')
              : t('billing.renewSoon', { days: renewDaysRemaining })}
          </Text>
        </Alert>
      )}

      {data.bonusUntil && (
        <BonusBanner
          bonus={{
            until: data.bonusUntil,
            reason: data.bonusReason ?? null,
            grantedByEmail: null,
          }}
          plan={data.plan}
        />
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
                  {data.plan}
                </Title>
              </Group>
              {renewsLabel && (
                <Text size="xs" c="dimmed" data-testid="renews-at">
                  {t('billing.renewsOn', { date: renewsLabel })}
                </Text>
              )}
            </Stack>
            {isPaid && (
              <Button variant="light" loading={portal.isPending} onClick={() => portal.mutate()}>
                {t('billing.managePortal') ?? 'Manage subscription'}
              </Button>
            )}
          </Group>
          <Text size="xs" c="dimmed">
            {t('billing.userScopeHint') ??
              'Your plan covers every organization you own. Granting a teammate ownership of a separate org gives them their own billing.'}
          </Text>
        </Stack>
      </Card>

      <Title order={4}>{t('billing.pickPlan')}</Title>
      <SimpleGrid cols={{ base: 1, sm: 2, md: 4 }} spacing="md">
        {plans.data.tiers.map((tier) => (
          <TierCard
            key={tier.plan}
            tier={tier}
            isCurrent={tier.plan === data.plan}
            isSelected={selectedTier === tier.plan}
            onSelect={() => setSelectedTier(tier.plan)}
            selectedDuration={selectedDuration}
            onSelectDuration={setSelectedDuration}
            paying={
              (cryptoCheckout.isPending && cryptoCheckout.variables?.tier === tier.plan) ||
              cardCheckout.isPending
            }
            onPayCrypto={() => {
              if (!isPaidTier(tier.plan)) return;
              cryptoCheckout.mutate({ tier: tier.plan, duration: selectedDuration });
            }}
            onPayCard={() => {
              if (!isPaidTier(tier.plan)) return;
              cardCheckout.mutate();
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
  onPayCrypto,
  onPayCard,
}: {
  tier: PlanTierInfo;
  isCurrent: boolean;
  isSelected: boolean;
  onSelect: () => void;
  selectedDuration: PlanDuration;
  onSelectDuration: (d: PlanDuration) => void;
  paying: boolean;
  onPayCrypto: () => void;
  onPayCard: () => void;
}) {
  const { t } = useTranslation();
  const isPaid = isPaidTier(tier.plan);
  const isPopular = tier.plan === 'pro';
  const offer = tier.durations.find((d) => d.months === selectedDuration);
  const isHighlighted = isSelected;

  return (
    <Card
      withBorder
      padding="lg"
      radius="md"
      data-testid={`tier-card-${tier.plan}`}
      data-selected={isSelected}
      onClick={isPaid ? onSelect : undefined}
      style={{
        cursor: isPaid ? 'pointer' : 'default',
        borderColor: isHighlighted ? 'var(--mantine-color-blue-6)' : undefined,
        borderWidth: isHighlighted ? 2 : 1,
      }}
    >
      <Stack gap="sm">
        <Group gap="xs" mih={22}>
          {isCurrent && (
            <Badge variant="filled" color="teal" size="sm">
              {t('billing.tierStarterCurrent')}
            </Badge>
          )}
          {isPopular && !isCurrent && (
            <Badge variant="filled" color="blue" size="sm">
              {t('billing.popularBadge')}
            </Badge>
          )}
        </Group>
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
              ? t('billing.perMonthFromCents', { price: formatDollars(tier.monthlyPriceCents) })
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
            {tier.unlimitedOrgs
              ? t('billing.tierOrgsUnlimited')
              : t('billing.tierOrgsLine', { count: tier.orgCap })}
          </List.Item>
          <List.Item>{t('billing.tierRetentionLine', { days: tier.retentionDays })}</List.Item>
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
                label: `${d.months}mo`,
                value: String(d.months),
              }))}
            />
            <Button
              fullWidth
              loading={paying}
              onClick={(e) => {
                e.stopPropagation();
                onPayCrypto();
              }}
              data-testid={`pay-${tier.plan}-${selectedDuration}`}
            >
              {t('billing.payTotalButton', { total: formatDollars(offer?.amountCents ?? 0) })}
            </Button>
            <Button
              fullWidth
              variant="default"
              onClick={(e) => {
                e.stopPropagation();
                onPayCard();
              }}
            >
              {t('billing.subscribeCard') ?? 'Subscribe via card'}
            </Button>
            {offer && offer.savePercent > 0 && (
              <Text
                size="xs"
                c="teal.4"
                fw={600}
                ta="center"
                data-testid={`save-hint-${tier.plan}-${selectedDuration}`}
              >
                {t('billing.savingsHint', { percent: offer.savePercent })}
              </Text>
            )}
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
