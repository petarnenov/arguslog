import {
  Anchor,
  Badge,
  Code,
  Collapse,
  Group,
  Stack,
  Table,
  Text,
  UnstyledButton,
} from '@mantine/core';
import { IconChevronDown, IconChevronRight } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';

import type { RawBreadcrumb } from './Breadcrumbs';
import type { EventMeta } from './EventDetails';
import { explorerForChain } from './explorer-urls';

/**
 * Web3-aware event detail panel. Detects {@code web3.*} tags + {@code web3.error}
 * breadcrumbs (emitted by {@code @arguslog/sdk-web3}) and renders the rich Web3 context
 * with chain / wallet / contract identification, the decoded error, and clickable links
 * into the chain's block explorer. Falls back silently — events without any Web3 tags
 * cause this panel to render nothing.
 *
 * <p>The data shown comes from two sources:
 *
 * <ul>
 *   <li><strong>Tags</strong> ({@code event.tags['web3.kind' | 'web3.chain' | 'web3.wallet'
 *       | 'web3.contract' | 'web3.source']}) — searchable filterable identifiers stamped by
 *       {@code captureWeb3Error} on every Web3 event.</li>
 *   <li><strong>Breadcrumb data</strong> — the most recent {@code web3.error} crumb's data
 *       payload carries the structurally-decoded error: {@code errorName}, {@code args},
 *       {@code reason}, {@code metaMessages}, Anchor's {@code errorCode / errorNumber /
 *       origin}, Solana program {@code logs} + {@code signature}, etc.</li>
 * </ul>
 */
export interface Web3Summary {
  kind?: string;
  source?: string;
  chain?: { id: string | number; name?: string };
  wallet?: string;
  contract?: string;
  functionName?: string;
  /** EVM revert info */
  errorName?: string;
  errorReason?: string;
  /** Anchor info */
  anchorErrorCode?: string;
  anchorErrorNumber?: number;
  anchorErrorMessage?: string;
  anchorOrigin?: string;
  /** Solana program info */
  programId?: string;
  customErrorCode?: number;
  customErrorHex?: string;
  /** Generic */
  message?: string;
  args?: unknown;
  revertData?: unknown;
  metaMessages?: unknown;
  comparedValues?: unknown;
  transactionHash?: string;
  signature?: string;
  logs?: string[];
  errorLogs?: string[];
  account?: string;
  gasEstimate?: string | number;
}

const KIND_COLOR: Record<string, string> = {
  'user.rejected': 'gray',
  'wallet.notConnected': 'gray',
  'chain.mismatch': 'yellow',
  'contract.reverted': 'orange',
  'tx.executionFailed': 'red',
  'tx.replacementUnderpriced': 'yellow',
  'tx.nonceExpired': 'yellow',
  'tx.insufficientFunds': 'orange',
  'gas.estimateFailed': 'orange',
  'rpc.rateLimit': 'yellow',
  'rpc.timeout': 'yellow',
  'rpc.invalidParams': 'red',
  'solana.programError': 'red',
  'solana.anchorError': 'orange',
  'solana.simulationFailed': 'orange',
  'solana.blockhashExpired': 'yellow',
  'solana.computeBudgetExceeded': 'red',
  'solana.insufficientLamports': 'orange',
};

export function extractWeb3Summary(
  meta: EventMeta,
  breadcrumbs: readonly RawBreadcrumb[],
): Web3Summary | undefined {
  const tagKind = meta.tags['web3.kind'];
  const errorCrumb = [...breadcrumbs].reverse().find((b) => b.category === 'web3.error');
  if (!tagKind && !errorCrumb) return undefined;

  const data = errorCrumb?.data ?? {};
  const summary: Web3Summary = {
    kind: tagKind ?? readString(data, 'kind'),
    source: meta.tags['web3.source'] ?? readString(data, 'source'),
    chain: parseChain(meta.tags['web3.chain'], data['chain']),
    wallet: meta.tags['web3.wallet'] ?? readString(data, 'wallet'),
    contract: meta.tags['web3.contract'] ?? readString(data, 'contract'),
    functionName: readString(data, 'functionName'),
    errorName: readString(data, 'errorName'),
    errorReason: readString(data, 'reason'),
    anchorErrorCode: readString(data, 'errorCode'),
    anchorErrorNumber: readNumber(data, 'errorNumber'),
    anchorErrorMessage: readString(data, 'errorMessage'),
    anchorOrigin: readString(data, 'origin'),
    programId: readString(data, 'programId'),
    customErrorCode: readNumber(data, 'customErrorCode'),
    customErrorHex: readString(data, 'customErrorHex'),
    message: readString(data, 'message'),
    args: data['args'],
    revertData: data['revertData'],
    metaMessages: data['metaMessages'],
    comparedValues: data['comparedValues'],
    transactionHash: readString(data, 'transactionHash'),
    signature: readString(data, 'signature'),
    logs: readStringArray(data, 'logs'),
    errorLogs: readStringArray(data, 'errorLogs'),
    account: readString(data, 'account'),
    gasEstimate: (data['gasEstimate'] as string | number | undefined) ?? undefined,
  };
  return summary;
}

export interface Web3PanelProps {
  summary: Web3Summary;
}

export function Web3Panel({ summary }: Web3PanelProps) {
  const { t } = useTranslation();
  const explorer = explorerForChain(summary.chain?.id);
  const chainLabel =
    summary.chain?.name ?? explorer?.name ?? (summary.chain?.id !== undefined ? String(summary.chain.id) : null);
  const kindColor = (summary.kind && KIND_COLOR[summary.kind]) || 'red';

  // Decode the most useful one-liner: Anchor error → "ConstraintHasOne: …", EVM → errorName,
  // custom Solana program error → "0x1f4 (500)", fallback → message.
  const headline =
    summary.anchorErrorCode && summary.anchorErrorMessage
      ? `${summary.anchorErrorCode}: ${summary.anchorErrorMessage}`
      : summary.errorName
        ? summary.errorReason
          ? `${summary.errorName}: ${summary.errorReason}`
          : summary.errorName
        : summary.customErrorHex
          ? `${t('issueDetail.web3.customError')} ${summary.customErrorHex}${
              summary.customErrorCode !== undefined ? ` (${summary.customErrorCode})` : ''
            }`
          : summary.message ?? null;

  return (
    <Stack gap="xs" data-testid="web3-panel">
      <Text size="xs" fw={500} c="dimmed">
        {t('issueDetail.web3.title')}
      </Text>

      <Group gap="xs" wrap="wrap">
        {summary.kind && (
          <Badge size="sm" color={kindColor} variant="filled" data-testid="web3-kind-badge">
            {summary.kind}
          </Badge>
        )}
        {chainLabel && (
          <Badge size="sm" color="blue" variant="light">
            {t('issueDetail.web3.chain')}: {chainLabel}
          </Badge>
        )}
        {summary.wallet && (
          <Badge size="sm" color="grape" variant="light">
            {t('issueDetail.web3.wallet')}: {summary.wallet}
          </Badge>
        )}
        {summary.source && (
          <Badge size="sm" color="gray" variant="outline">
            via {summary.source}
          </Badge>
        )}
      </Group>

      {headline && (
        <Code
          block
          style={{
            fontSize: 13,
            fontWeight: 500,
            padding: 10,
            background: 'var(--mantine-color-red-0)',
            color: 'var(--mantine-color-red-9)',
          }}
          data-testid="web3-headline"
        >
          {headline}
        </Code>
      )}

      <Table withTableBorder withColumnBorders={false} data-testid="web3-fields">
        <Table.Tbody>
          {summary.contract && (
            <KvRow
              label={summary.programId ? t('issueDetail.web3.program') : t('issueDetail.web3.contract')}
              value={summary.contract}
              href={explorer?.address(summary.contract)}
              testId="web3-contract"
            />
          )}
          {summary.functionName && (
            <KvRow
              label={t('issueDetail.web3.function')}
              value={summary.functionName}
              testId="web3-function"
            />
          )}
          {summary.account && (
            <KvRow
              label={t('issueDetail.web3.account')}
              value={summary.account}
              href={explorer && summary.account ? explorer.address(summary.account) : undefined}
              testId="web3-account"
            />
          )}
          {summary.transactionHash && (
            <KvRow
              label={t('issueDetail.web3.txHash')}
              value={summary.transactionHash}
              href={explorer?.tx(summary.transactionHash)}
              testId="web3-tx-hash"
            />
          )}
          {summary.signature && (
            <KvRow
              label={t('issueDetail.web3.signature')}
              value={summary.signature}
              href={explorer?.tx(summary.signature)}
              testId="web3-signature"
            />
          )}
          {summary.anchorOrigin && (
            <KvRow
              label={t('issueDetail.web3.anchorOrigin')}
              value={summary.anchorOrigin}
              testId="web3-anchor-origin"
            />
          )}
          {summary.anchorErrorNumber !== undefined && (
            <KvRow
              label={t('issueDetail.web3.anchorErrorNumber')}
              value={String(summary.anchorErrorNumber)}
              testId="web3-anchor-number"
            />
          )}
          {summary.gasEstimate !== undefined && (
            <KvRow
              label={t('issueDetail.web3.gasEstimate')}
              value={String(summary.gasEstimate)}
              testId="web3-gas"
            />
          )}
        </Table.Tbody>
      </Table>

      {hasContent(summary.args) && (
        <CollapsibleJson
          label={t('issueDetail.web3.args')}
          value={summary.args}
          testId="web3-args"
        />
      )}
      {hasContent(summary.comparedValues) && (
        <CollapsibleJson
          label={t('issueDetail.web3.comparedValues')}
          value={summary.comparedValues}
          testId="web3-compared-values"
        />
      )}
      {hasContent(summary.metaMessages) && (
        <CollapsibleJson
          label={t('issueDetail.web3.metaMessages')}
          value={summary.metaMessages}
          testId="web3-meta-messages"
        />
      )}
      {hasContent(summary.revertData) && (
        <CollapsibleJson
          label={t('issueDetail.web3.revertData')}
          value={summary.revertData}
          testId="web3-revert-data"
        />
      )}
      {summary.errorLogs && summary.errorLogs.length > 0 && (
        <CollapsibleLogs
          label={t('issueDetail.web3.errorLogs')}
          logs={summary.errorLogs}
          testId="web3-error-logs"
        />
      )}
      {summary.logs && summary.logs.length > 0 && (
        <CollapsibleLogs
          label={t('issueDetail.web3.programLogs')}
          logs={summary.logs}
          testId="web3-program-logs"
        />
      )}
    </Stack>
  );
}

interface KvRowProps {
  label: string;
  value: string;
  href?: string;
  testId?: string;
}

function KvRow({ label, value, href, testId }: KvRowProps) {
  return (
    <Table.Tr data-testid={testId}>
      <Table.Td style={{ width: 140, paddingLeft: 8, paddingRight: 8 }}>
        <Text size="xs" c="dimmed">
          {label}
        </Text>
      </Table.Td>
      <Table.Td style={{ paddingLeft: 8, paddingRight: 8 }}>
        {href ? (
          <Anchor
            size="xs"
            href={href}
            target="_blank"
            rel="noreferrer noopener"
            ff="monospace"
            style={{ wordBreak: 'break-all' }}
          >
            {value}
          </Anchor>
        ) : (
          <Text size="xs" ff="monospace" style={{ wordBreak: 'break-all' }}>
            {value}
          </Text>
        )}
      </Table.Td>
    </Table.Tr>
  );
}

interface CollapsibleJsonProps {
  label: string;
  value: unknown;
  testId: string;
}

function CollapsibleJson({ label, value, testId }: CollapsibleJsonProps) {
  const [expanded, setExpanded] = useState(false);
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
          {safeStringify(value)}
        </Code>
      </Collapse>
    </Stack>
  );
}

function CollapsibleLogs({
  label,
  logs,
  testId,
}: {
  label: string;
  logs: readonly string[];
  testId: string;
}) {
  const [expanded, setExpanded] = useState(false);
  const preview = logs.length > 0 ? `${label} (${logs.length})` : label;
  return (
    <Stack gap={2} data-testid={testId}>
      <UnstyledButton
        onClick={() => setExpanded((v) => !v)}
        aria-expanded={expanded}
        style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}
      >
        {expanded ? <IconChevronDown size={12} /> : <IconChevronRight size={12} />}
        <Text size="xs" c="dimmed">
          {preview}
        </Text>
      </UnstyledButton>
      <Collapse in={expanded}>
        <Code block style={{ fontSize: 11, maxWidth: 720, whiteSpace: 'pre-wrap' }}>
          {logs.join('\n')}
        </Code>
      </Collapse>
    </Stack>
  );
}

function readString(obj: Record<string, unknown>, key: string): string | undefined {
  const v = obj[key];
  return typeof v === 'string' ? v : undefined;
}

function readNumber(obj: Record<string, unknown>, key: string): number | undefined {
  const v = obj[key];
  return typeof v === 'number' ? v : undefined;
}

function readStringArray(obj: Record<string, unknown>, key: string): string[] | undefined {
  const v = obj[key];
  if (!Array.isArray(v)) return undefined;
  return v.filter((item): item is string => typeof item === 'string');
}

function parseChain(
  tag: string | undefined,
  fromBreadcrumb: unknown,
): Web3Summary['chain'] | undefined {
  // Breadcrumb shape wins (richer): { id, name }. The tag is just a string id/name.
  if (fromBreadcrumb && typeof fromBreadcrumb === 'object') {
    const c = fromBreadcrumb as Record<string, unknown>;
    const id = c['id'];
    if (typeof id === 'number' || typeof id === 'string') {
      return { id, name: typeof c['name'] === 'string' ? c['name'] : undefined };
    }
  }
  if (tag) {
    // Tag could be either a numeric chain id ("1", "8453") or a name ("Ethereum mainnet" /
    // "mainnet-beta"). Try numeric first; fall back to keeping the string as id so the
    // explorer-url lookup gets a chance.
    if (/^\d+$/.test(tag)) return { id: Number.parseInt(tag, 10) };
    return { id: tag, name: tag };
  }
  return undefined;
}

function hasContent(value: unknown): boolean {
  if (value === undefined || value === null) return false;
  if (Array.isArray(value)) return value.length > 0;
  if (typeof value === 'object') return Object.keys(value as Record<string, unknown>).length > 0;
  return true;
}

function safeStringify(value: unknown): string {
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return '<unserializable>';
  }
}
