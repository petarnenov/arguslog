import { Alert, Group, Stack, Text } from '@mantine/core';
import { IconGift } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';

import { formatRelativeTime } from '../lib/relativeTime';

/**
 * Banner shown when the signed-in user has an admin-granted tier in effect with a future
 * expiry. Sits compact in the sidebar above the org switcher so the user understands their
 * elevated tier is admin-granted, not paid.
 */
export interface BonusBannerProps {
  /** ISO-8601 timestamp when the grant expires. */
  expiresAt: string;
  /** Optional reason captured at grant time. */
  reason: string | null;
  /** Current tier the user is on (regular / silver / gold / platinum). */
  tier: string;
  /** "compact" → single-line for the sidebar; default is the full banner. */
  variant?: 'compact' | 'full';
}

export function BonusBanner({ expiresAt, reason, tier, variant = 'full' }: BonusBannerProps) {
  const { t, i18n } = useTranslation();
  const untilRel = formatRelativeTime(expiresAt, i18n.language || 'en');
  const untilAbs = new Date(expiresAt).toLocaleString(i18n.language || 'en');

  if (variant === 'compact') {
    return (
      <Alert
        color="violet"
        variant="light"
        py={6}
        px="sm"
        icon={<IconGift size={14} />}
        data-testid="bonus-banner-compact"
        styles={{ message: { fontSize: 12 } }}
      >
        <Group gap={6} wrap="nowrap">
          <Text size="xs" fw={600} tt="uppercase">
            {tier}
          </Text>
          <Text size="xs" c="dimmed">
            {t('bonus.compactSuffix', { until: untilRel })}
          </Text>
        </Group>
      </Alert>
    );
  }

  return (
    <Alert color="violet" variant="light" icon={<IconGift size={20} />} data-testid="bonus-banner">
      <Stack gap={4}>
        <Text fw={600}>{t('bonus.title', { plan: tier.toUpperCase() })}</Text>
        <Text size="sm">{t('bonus.body', { until: untilAbs, untilRel })}</Text>
        {reason && (
          <Text size="xs" c="dimmed" fs="italic">
            {t('bonus.reasonPrefix')}: {reason}
          </Text>
        )}
      </Stack>
    </Alert>
  );
}
