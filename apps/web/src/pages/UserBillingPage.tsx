import {
  Alert,
  Badge,
  Button,
  Card,
  Group,
  Loader,
  Stack,
  Text,
  Title,
} from '@mantine/core';
import { useMutation } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';

import { openMePortal, startMeCheckout } from '../api/billing';
import { ApiError } from '../api/client';
import { useMe } from '../api/queries';
import { BonusBanner } from '../components/BonusBanner';

const PAID_TIERS = new Set(['starter', 'pro', 'business', 'enterprise']);

function errorMessage(err: unknown): string | null {
  if (!err) return null;
  if (err instanceof ApiError) return err.problem.detail ?? err.problem.title;
  return String(err);
}

/**
 * User-level Billing page (V26+). Reads plan / renew / bonus / grace from /me and routes
 * checkout + portal through /me/billing — the backend resolves the user's primary owned org
 * under the hood. Per-org BillingPage at /orgs/:slug/billing keeps the full tier-card + crypto
 * flow for now; this page is the simpler "I am a user, where do I pay" entry point.
 */
export function UserBillingPage() {
  const { t } = useTranslation();
  const me = useMe();

  const checkout = useMutation({
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

  if (me.isLoading) {
    return (
      <Group p="md">
        <Loader size="sm" />
      </Group>
    );
  }
  if (me.isError || !me.data) {
    return (
      <Alert color="red" variant="light">
        {t('errors.generic')}
      </Alert>
    );
  }

  const data = me.data;
  const isPaid = PAID_TIERS.has(data.plan);
  const renewsAt = data.planRenewsAt
    ? new Date(data.planRenewsAt).toLocaleDateString()
    : null;
  const graceUntil = data.paymentGraceUntil
    ? new Date(data.paymentGraceUntil).toLocaleDateString()
    : null;
  const checkoutError = errorMessage(checkout.error) ?? errorMessage(portal.error);

  return (
    <Stack maw={900}>
      <Title order={3}>{t('billing.title')}</Title>

      {graceUntil && (
        <Alert color="red" variant="filled">
          <Text size="sm">{t('billing.paymentFailedTitle')} — {graceUntil}</Text>
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
              <Group gap="xs">
                <Title order={2} tt="capitalize">
                  {data.plan}
                </Title>
                {isPaid && <Badge color="teal">{t('billing.tierStarterCurrent')}</Badge>}
              </Group>
              {renewsAt && (
                <Text size="xs" c="dimmed">
                  {t('billing.renewsOn', { date: renewsAt })}
                </Text>
              )}
            </Stack>
            <Stack gap="xs">
              {isPaid ? (
                <Button
                  variant="light"
                  loading={portal.isPending}
                  onClick={() => portal.mutate()}
                >
                  {t('billing.managePortal') ?? 'Manage subscription'}
                </Button>
              ) : (
                <Button
                  loading={checkout.isPending}
                  onClick={() => checkout.mutate()}
                >
                  {t('billing.subscribeCard') ?? 'Subscribe via card'}
                </Button>
              )}
            </Stack>
          </Group>

          {checkoutError && (
            <Alert color="red" variant="light">
              {checkoutError}
            </Alert>
          )}

          <Text size="xs" c="dimmed">
            {t('billing.userScopeHint') ??
              'Your plan covers every organization you own. Granting a teammate ownership of a separate org gives them their own billing.'}
          </Text>
        </Stack>
      </Card>
    </Stack>
  );
}
