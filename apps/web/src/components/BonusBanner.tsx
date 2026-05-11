import { Alert, Group, Stack, Text } from '@mantine/core';
import { IconGift } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';

import type { BonusInfo } from '../api/billing';
import { formatRelativeTime } from '../lib/relativeTime';

/**
 * Banner shown when the current org has an admin-granted bonus plan in effect. Sits in the
 * BillingPage above the plan card and (compact variant) in the sidebar above the org switcher
 * so the user understands their elevated tier is courtesy, not a paid subscription.
 */
export interface BonusBannerProps {
  bonus: BonusInfo;
  plan: string;
  /** "compact" → single-line for the sidebar; default is the full banner for the BillingPage. */
  variant?: 'compact' | 'full';
}

export function BonusBanner({ bonus, plan, variant = 'full' }: BonusBannerProps) {
  const { t, i18n } = useTranslation();
  const untilRel = formatRelativeTime(bonus.until, i18n.language || 'en');
  const untilAbs = new Date(bonus.until).toLocaleString(i18n.language || 'en');

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
            {plan}
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
        <Text fw={600}>{t('bonus.title', { plan: plan.toUpperCase() })}</Text>
        <Text size="sm">{t('bonus.body', { until: untilAbs, untilRel })}</Text>
        {bonus.reason && (
          <Text size="xs" c="dimmed" fs="italic">
            {t('bonus.reasonPrefix')}: {bonus.reason}
          </Text>
        )}
        {bonus.grantedByEmail && (
          <Text size="xs" c="dimmed">
            {t('bonus.grantedBy', { email: bonus.grantedByEmail })}
          </Text>
        )}
      </Stack>
    </Alert>
  );
}
