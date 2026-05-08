import { Anchor, Badge, Code, Collapse, Group, Stack, Text, UnstyledButton } from '@mantine/core';
import { IconChevronDown, IconChevronRight } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';

/**
 * Lifts the structured-but-rarely-rendered fields off an event payload — release,
 * environment, user, tags, request, contexts, and the JVM-only {@code extra} bag — into a
 * shape the {@link EventDetailsView} can render with no per-field guards in JSX.
 *
 * <p>All fields are optional: an event from the JVM SDK has tags + maybe a user id, an
 * event from the browser SDK has request + contexts, etc. The view only shows sections
 * that have content, so missing data never produces empty placeholder rows.
 */
export interface EventMeta {
  release?: string;
  environment?: string;
  user?: { id?: string; email?: string; username?: string };
  tags: Record<string, string>;
  request?: { url?: string; userAgent?: string };
  contexts: Record<string, Record<string, unknown>>;
  extra: Record<string, unknown>;
}

export function extractEventMeta(payload: unknown): EventMeta {
  if (!payload || typeof payload !== 'object') {
    return { tags: {}, contexts: {}, extra: {} };
  }
  const p = payload as Record<string, unknown>;
  return {
    release: typeof p.release === 'string' ? p.release : undefined,
    environment: typeof p.environment === 'string' ? p.environment : undefined,
    user: extractUser(p.user),
    tags: extractStringRecord(p.tags),
    request: extractRequest(p.request),
    contexts: extractRecordOfRecords(p.contexts),
    extra: extractAnyRecord(p.extra),
  };
}

export function hasAnyMeta(meta: EventMeta): boolean {
  return (
    meta.release != null ||
    meta.environment != null ||
    meta.user != null ||
    Object.keys(meta.tags).length > 0 ||
    meta.request != null ||
    Object.keys(meta.contexts).length > 0 ||
    Object.keys(meta.extra).length > 0
  );
}

export interface EventDetailsViewProps {
  meta: EventMeta;
}

export function EventDetailsView({ meta }: EventDetailsViewProps) {
  const { t } = useTranslation();
  if (!hasAnyMeta(meta)) return null;

  const userLabel = meta.user
    ? (meta.user.email ?? meta.user.username ?? meta.user.id ?? null)
    : null;
  const tagEntries = Object.entries(meta.tags);
  const contextEntries = Object.entries(meta.contexts);
  const extraEntries = Object.entries(meta.extra);

  return (
    <Stack gap={6} data-testid="event-details">
      <Text size="xs" fw={500} c="dimmed">
        {t('issueDetail.eventDetails.title')}
      </Text>

      {(meta.release != null || meta.environment != null || userLabel != null) && (
        <Group gap="xs" wrap="wrap">
          {meta.release != null && (
            <MetaPill label={t('issueDetail.eventDetails.release')} value={meta.release} />
          )}
          {meta.environment != null && (
            <MetaPill label={t('issueDetail.eventDetails.environment')} value={meta.environment} />
          )}
          {userLabel != null && (
            <MetaPill label={t('issueDetail.eventDetails.user')} value={userLabel} />
          )}
        </Group>
      )}

      {tagEntries.length > 0 && (
        <Group gap="xs" wrap="wrap" data-testid="event-details-tags">
          <Text size="xs" c="dimmed">
            {t('issueDetail.eventDetails.tags')}:
          </Text>
          {tagEntries.map(([k, v]) => (
            <Badge key={k} size="xs" variant="light" color="gray">
              {k}: {v}
            </Badge>
          ))}
        </Group>
      )}

      {meta.request != null && (
        <Group gap="xs" wrap="wrap" data-testid="event-details-request">
          <Text size="xs" c="dimmed">
            {t('issueDetail.eventDetails.request')}:
          </Text>
          {meta.request.url != null && (
            <Anchor
              size="xs"
              href={meta.request.url}
              target="_blank"
              rel="noreferrer noopener"
              style={{ wordBreak: 'break-all' }}
            >
              {meta.request.url}
            </Anchor>
          )}
          {meta.request.userAgent != null && (
            <Text size="xs" c="dimmed">
              ({meta.request.userAgent})
            </Text>
          )}
        </Group>
      )}

      {contextEntries.map(([name, value]) => (
        <CollapsibleJson
          key={`ctx-${name}`}
          label={`${t('issueDetail.eventDetails.contexts')}: ${name}`}
          value={value}
          testId={`event-details-context-${name}`}
        />
      ))}

      {extraEntries.length > 0 && (
        <CollapsibleJson
          label={t('issueDetail.eventDetails.extra')}
          value={meta.extra}
          testId="event-details-extra"
        />
      )}
    </Stack>
  );
}

interface MetaPillProps {
  label: string;
  value: string;
}

function MetaPill({ label, value }: MetaPillProps) {
  return (
    <Group gap={4} wrap="nowrap">
      <Text size="xs" c="dimmed">
        {label}:
      </Text>
      <Code style={{ fontSize: 11 }}>{value}</Code>
    </Group>
  );
}

interface CollapsibleJsonProps {
  label: string;
  value: unknown;
  testId: string;
}

function CollapsibleJson({ label, value, testId }: CollapsibleJsonProps) {
  const [expanded, setExpanded] = useState(false);
  const json = safeStringify(value);

  return (
    <Stack gap={2} data-testid={testId}>
      <UnstyledButton
        onClick={() => setExpanded((v) => !v)}
        aria-expanded={expanded}
        style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}
      >
        {expanded ? <IconChevronDown size={12} /> : <IconChevronRight size={12} />}
        <Text size="xs" c="dimmed">
          {label}
        </Text>
      </UnstyledButton>
      <Collapse in={expanded}>
        <Code block style={{ fontSize: 11, maxWidth: 720, whiteSpace: 'pre-wrap' }}>
          {json}
        </Code>
      </Collapse>
    </Stack>
  );
}

function extractUser(raw: unknown): { id?: string; email?: string; username?: string } | undefined {
  if (!raw || typeof raw !== 'object') return undefined;
  const r = raw as Record<string, unknown>;
  const out: { id?: string; email?: string; username?: string } = {};
  if (typeof r.id === 'string') out.id = r.id;
  if (typeof r.email === 'string') out.email = r.email;
  if (typeof r.username === 'string') out.username = r.username;
  return Object.keys(out).length > 0 ? out : undefined;
}

function extractStringRecord(raw: unknown): Record<string, string> {
  if (!raw || typeof raw !== 'object') return {};
  const out: Record<string, string> = {};
  for (const [k, v] of Object.entries(raw as Record<string, unknown>)) {
    if (typeof v === 'string') out[k] = v;
    else if (typeof v === 'number' || typeof v === 'boolean') out[k] = String(v);
  }
  return out;
}

function extractRequest(raw: unknown): { url?: string; userAgent?: string } | undefined {
  if (!raw || typeof raw !== 'object') return undefined;
  const r = raw as Record<string, unknown>;
  const out: { url?: string; userAgent?: string } = {};
  if (typeof r.url === 'string') out.url = r.url;
  if (typeof r.userAgent === 'string') out.userAgent = r.userAgent;
  return Object.keys(out).length > 0 ? out : undefined;
}

function extractRecordOfRecords(raw: unknown): Record<string, Record<string, unknown>> {
  if (!raw || typeof raw !== 'object') return {};
  const out: Record<string, Record<string, unknown>> = {};
  for (const [k, v] of Object.entries(raw as Record<string, unknown>)) {
    if (v && typeof v === 'object' && !Array.isArray(v)) {
      out[k] = v as Record<string, unknown>;
    }
  }
  return out;
}

function extractAnyRecord(raw: unknown): Record<string, unknown> {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return {};
  return { ...(raw as Record<string, unknown>) };
}

function safeStringify(value: unknown): string {
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return '<unserializable>';
  }
}
