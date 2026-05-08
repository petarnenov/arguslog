import { Badge, Code, Stack, Table, Text } from '@mantine/core';
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
   * cross-reference absolute timestamps.
   */
  referenceTime: number;
}

export function BreadcrumbsView({ breadcrumbs, referenceTime }: BreadcrumbsViewProps) {
  const { t } = useTranslation();
  if (breadcrumbs.length === 0) return null;

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
          {breadcrumbs.map((bc, idx) => {
            const level = bc.level ?? 'info';
            const dataPreview = bc.data ? previewData(bc.data) : null;
            return (
              <Table.Tr key={idx}>
                <Table.Td>
                  <Text size="xs" c="dimmed" ff="monospace">
                    {formatDelta(bc.timestamp, referenceTime)}
                  </Text>
                </Table.Td>
                <Table.Td>
                  <Badge size="xs" color={LEVEL_COLOR[level] ?? 'gray'} variant="light">
                    {level}
                  </Badge>
                </Table.Td>
                <Table.Td>
                  <Text size="xs">{bc.category ?? '—'}</Text>
                </Table.Td>
                <Table.Td>
                  <Stack gap={2}>
                    <Text size="xs">{bc.message ?? '—'}</Text>
                    {dataPreview && (
                      <Code style={{ fontSize: 11, maxWidth: 480 }}>{dataPreview}</Code>
                    )}
                  </Stack>
                </Table.Td>
              </Table.Tr>
            );
          })}
        </Table.Tbody>
      </Table>
    </Stack>
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

function previewData(data: Record<string, unknown>): string | null {
  try {
    const json = JSON.stringify(data);
    if (json === '{}') return null;
    return json.length > 200 ? `${json.slice(0, 200)}…` : json;
  } catch {
    return null;
  }
}
