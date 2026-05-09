import { addBreadcrumb, captureException, type Level } from '@arguslog/sdk-browser';

import { decodeEthersError } from './decode-ethers-error.js';
import { decodeViemError, jsonSafe } from './decode-viem-error.js';
import type { DecodedWeb3Error, Web3ErrorContext, Web3ErrorKind } from './types.js';

/**
 * Public API: capture a Web3 error with structured context. Decodes via the viem decoder
 * first (richer typed errors), falls back to the ethers v6 decoder, then to a generic shape
 * so the user code path always gets back a tracked event id.
 *
 * <p>The decoded payload lands in the breadcrumb stream as a {@code web3.error} entry —
 * the breadcrumb buffer is what the {@code captureException} below stamps onto the event,
 * which means the dashboard sees the rich structure (chain, contract, args, decoded revert)
 * sitting next to the raw error / stack trace.
 *
 * <p>Tags carry the searchable fields: {@code web3.kind}, {@code web3.chain}, {@code web3.wallet},
 * {@code web3.contract}. Filter the dashboard by {@code web3.kind:contract.reverted} to surface
 * every transaction revert across all customers, etc.
 */
export function captureWeb3Error(error: unknown, context: Web3ErrorContext = {}): string | undefined {
  const decoded = decodeViemError(error) ?? decodeEthersError(error) ?? genericDecode(error);

  // Breadcrumb captures the rich structure — the next captureException attaches all
  // breadcrumbs (incl this one) onto the event.
  try {
    addBreadcrumb({
      category: 'web3.error',
      message: decoded.shortMessage,
      level: levelFor(decoded.kind),
      data: {
        kind: decoded.kind,
        source: decoded.source,
        chain: context.chain,
        wallet: context.wallet,
        contract: context.contract,
        functionName: context.functionName,
        args: context.args ? jsonSafe(context.args) : undefined,
        account: context.account,
        transactionHash: context.transactionHash,
        gasEstimate: context.gasEstimate ? String(context.gasEstimate) : undefined,
        ...decoded.data,
        ...(context.extra ?? {}),
      },
    });
  } catch {
    // best-effort
  }

  const tags: Record<string, string> = { 'web3.kind': decoded.kind, 'web3.source': decoded.source };
  if (context.chain) {
    tags['web3.chain'] = context.chain.name ?? String(context.chain.id);
  }
  if (context.wallet) tags['web3.wallet'] = context.wallet;
  if (context.contract) tags['web3.contract'] = String(context.contract);

  return captureException(error, { level: levelFor(decoded.kind), tags });
}

function levelFor(kind: Web3ErrorKind): Level {
  // User-rejected is by-design behaviour; capturing as info keeps the alert noise down. Anything
  // else is at least a warning so it shows up on the dashboard's default "errors" filter.
  if (kind === 'user.rejected') return 'info';
  if (kind === 'rpc.rateLimit' || kind === 'chain.mismatch') return 'warning';
  return 'error';
}

function genericDecode(error: unknown): DecodedWeb3Error {
  const message =
    error instanceof Error
      ? error.message
      : typeof error === 'string'
        ? error
        : 'Unknown Web3 error';
  return {
    kind: 'unknown',
    shortMessage: message,
    data: { message },
    source: 'unknown',
  };
}
