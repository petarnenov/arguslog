import { ActionIcon, Badge, Code, Collapse, Stack, Table, Text, Tooltip } from '@mantine/core';
import {
  IconAlertTriangle,
  IconArrowsExchange,
  IconChevronDown,
  IconChevronRight,
  IconCoin,
  IconEye,
  IconLink,
  IconPlugConnected,
  IconSignature,
  IconWallet,
} from '@tabler/icons-react';
import { useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';

/**
 * One breadcrumb as it lands in the persisted event payload — mirror of {@link
 * Breadcrumb} in {@code packages/sdk-core/src/types.ts}. The browser SDK ships these via
 * its {@code breadcrumbs} integration (clicks, fetches, console). The JVM/Python SDKs
 * don't emit breadcrumbs yet, so the panel will stay empty for those events.
 */
export interface RawBreadcrumb {
  timestamp: number;
  category?: string;
  message?: string;
  level?: string;
  data?: Record<string, unknown>;
}

const LEVEL_COLOR: Record<string, string> = {
  fatal: 'red',
  error: 'orange',
  warning: 'yellow',
  info: 'blue',
  debug: 'gray',
};

interface CategoryStyle {
  color: string;
  icon?: ReactNode;
}

/**
 * Per-category visual styling. {@code web3.*} categories get distinctive icons/colors so
 * the timeline reads as a story even at a glance — wallet connect → switch chain → sign
 * → tx → confirm → error. Without these, every row is grey "info" and the user has to
 * read every message.
 */
const CATEGORY_STYLE: Record<string, CategoryStyle> = {
  'web3.wallet': { color: 'violet', icon: <IconWallet size={12} /> },
  'web3.walletconnect': { color: 'violet', icon: <IconPlugConnected size={12} /> },
  'web3.solana-wallet': { color: 'violet', icon: <IconWallet size={12} /> },
  'web3.tx': { color: 'cyan', icon: <IconCoin size={12} /> },
  'web3.sign': { color: 'teal', icon: <IconSignature size={12} /> },
  'web3.simulate': { color: 'grape', icon: <IconEye size={12} /> },
  'web3.confirm': { color: 'green', icon: <IconLink size={12} /> },
  'web3.read': { color: 'gray', icon: <IconEye size={12} /> },
  'web3.switch': { color: 'indigo', icon: <IconArrowsExchange size={12} /> },
  'web3.error': { color: 'red', icon: <IconAlertTriangle size={12} /> },
};

function categoryStyleFor(category: string | undefined): CategoryStyle {
  if (!category) return { color: 'gray' };
  if (CATEGORY_STYLE[category]) return CATEGORY_STYLE[category];
  // Catch-all for any future web3.* sub-category we haven't styled yet — keep them
  // visually grouped in violet so the user still recognizes them as the web3 family.
  if (category.startsWith('web3.')) return { color: 'violet' };
  return { color: 'gray' };
}

/** Returns the {@code payload.breadcrumbs} array, hardened against malformed payloads. */
export function extractBreadcrumbs(payload: unknown): RawBreadcrumb[] {
  if (!payload || typeof payload !== 'object') return [];
  const raw = (payload as { breadcrumbs?: unknown }).breadcrumbs;
  if (!Array.isArray(raw)) return [];
  return raw.filter((b): b is RawBreadcrumb => b != null && typeof b === 'object');
}

export interface BreadcrumbsViewProps {
  breadcrumbs: readonly RawBreadcrumb[];
  /**
   * Reference time the event was captured. Breadcrumb timestamps are formatted as a delta
   * (e.g. {@code -3.2s}) so the lead-up reads at a glance instead of forcing the user to
   * cross-reference absolute timestamps. The absolute timestamp is still available via a
   * tooltip on hover for when relative ordering isn't enough.
   */
  referenceTime: number;
}

export function BreadcrumbsView({ breadcrumbs, referenceTime }: BreadcrumbsViewProps) {
  const { t, i18n } = useTranslation();
  if (breadcrumbs.length === 0) return null;

  const absoluteFormatter = new Intl.DateTimeFormat(i18n.language || 'en', {
    dateStyle: 'medium',
    timeStyle: 'medium',
  });

  return (
    <Stack gap={4} data-testid="breadcrumbs">
      <Text size="xs" fw={500} c="dimmed">
        {t('issueDetail.breadcrumbs.title')}
      </Text>
      <Table withTableBorder withColumnBorders={false} striped>
        <Table.Thead>
          <Table.Tr>
            <Table.Th style={{ width: 80 }}>{t('issueDetail.breadcrumbs.time')}</Table.Th>
            <Table.Th style={{ width: 80 }}>{t('issueDetail.breadcrumbs.level')}</Table.Th>
            <Table.Th style={{ width: 120 }}>{t('issueDetail.breadcrumbs.category')}</Table.Th>
            <Table.Th>{t('issueDetail.breadcrumbs.message')}</Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {breadcrumbs.map((bc, idx) => (
            <BreadcrumbRow
              key={idx}
              breadcrumb={bc}
              referenceTime={referenceTime}
              absoluteFormatter={absoluteFormatter}
            />
          ))}
        </Table.Tbody>
      </Table>
    </Stack>
  );
}

interface BreadcrumbRowProps {
  breadcrumb: RawBreadcrumb;
  referenceTime: number;
  absoluteFormatter: Intl.DateTimeFormat;
}

function BreadcrumbRow({ breadcrumb, referenceTime, absoluteFormatter }: BreadcrumbRowProps) {
  const { t } = useTranslation();
  const [expanded, setExpanded] = useState(false);
  const level = breadcrumb.level ?? 'info';
  const hasData = hasMeaningfulData(breadcrumb.data);
  const dataJson = hasData ? safeStringify(breadcrumb.data!) : null;
  const absolute = Number.isFinite(breadcrumb.timestamp)
    ? absoluteFormatter.format(new Date(breadcrumb.timestamp))
    : null;

  return (
    <Table.Tr>
      <Table.Td>
        <Tooltip label={absolute ?? '?'} disabled={absolute == null} withArrow>
          <Text size="xs" c="dimmed" ff="monospace" style={{ cursor: 'help' }}>
            {formatDelta(breadcrumb.timestamp, referenceTime)}
          </Text>
        </Tooltip>
      </Table.Td>
      <Table.Td>
        <Badge size="xs" color={LEVEL_COLOR[level] ?? 'gray'} variant="light">
          {level}
        </Badge>
      </Table.Td>
      <Table.Td>
        {(() => {
          const style = categoryStyleFor(breadcrumb.category);
          if (!breadcrumb.category) return <Text size="xs">—</Text>;
          return (
            <Badge
              size="xs"
              variant="light"
              color={style.color}
              leftSection={style.icon ?? undefined}
            >
              {breadcrumb.category}
            </Badge>
          );
        })()}
      </Table.Td>
      <Table.Td>
        <Stack gap={2}>
          <Text size="xs" component="span">
            {breadcrumb.message ?? '—'}
            {hasData && (
              <ActionIcon
                size="xs"
                variant="subtle"
                color="gray"
                onClick={() => setExpanded((v) => !v)}
                aria-label={
                  expanded
                    ? t('issueDetail.breadcrumbs.hideData')
                    : t('issueDetail.breadcrumbs.viewData')
                }
                style={{ marginLeft: 6, verticalAlign: 'middle' }}
              >
                {expanded ? <IconChevronDown size={12} /> : <IconChevronRight size={12} />}
              </ActionIcon>
            )}
          </Text>
          {hasData && (
            <Collapse in={expanded}>
              <Code block style={{ fontSize: 11, maxWidth: 640, whiteSpace: 'pre-wrap' }}>
                {dataJson}
              </Code>
            </Collapse>
          )}
        </Stack>
      </Table.Td>
    </Table.Tr>
  );
}

function formatDelta(timestamp: number, referenceTime: number): string {
  if (!Number.isFinite(timestamp) || !Number.isFinite(referenceTime)) return '?';
  const deltaMs = timestamp - referenceTime;
  const seconds = deltaMs / 1000;
  if (Math.abs(seconds) < 60) return `${seconds.toFixed(1)}s`;
  const minutes = seconds / 60;
  if (Math.abs(minutes) < 60) return `${minutes.toFixed(1)}m`;
  const hours = minutes / 60;
  return `${hours.toFixed(1)}h`;
}

function hasMeaningfulData(data: Record<string, unknown> | undefined): boolean {
  if (!data) return false;
  return Object.keys(data).length > 0;
}

function safeStringify(data: Record<string, unknown>): string {
  try {
    return JSON.stringify(data, null, 2);
  } catch {
    return '<unserializable>';
  }
}
