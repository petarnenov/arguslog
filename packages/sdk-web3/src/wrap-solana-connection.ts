import { captureWeb3Error } from './capture-web3-error.js';
import type { ChainInfo, WalletKind, Web3ErrorContext } from './types.js';

/**
 * Wraps a {@code @solana/web3.js} {@code Connection} so the methods that actually fail
 * with rich errors are auto-instrumented. Read-only RPC fan-out ({@code getBalance},
 * {@code getAccountInfo}, etc.) passes through unwrapped — those are already breadcrumbed
 * by the {@code fetch} integration in {@code @arguslog/sdk-browser}.
 *
 * <p>Tracked methods:
 *
 * <ul>
 *   <li>{@code sendTransaction} — main dispatch path. The error here is typically a
 *       {@code SendTransactionError} carrying logs + signature, decoded by the Solana
 *       decoder.</li>
 *   <li>{@code sendRawTransaction} — pre-signed transaction submit.</li>
 *   <li>{@code simulateTransaction} — preflight; surfaces {@code solana.simulationFailed}
 *       or unwraps an Anchor error from the simulation logs.</li>
 *   <li>{@code confirmTransaction} — confirmation wait; throws on
 *       {@code TransactionExpiredBlockheightExceededError} which decodes to
 *       {@code solana.blockhashExpired}.</li>
 * </ul>
 */
const TRACKED_METHODS = new Set([
  'sendTransaction',
  'sendRawTransaction',
  'simulateTransaction',
  'confirmTransaction',
]);

export interface WrapSolanaConnectionOptions {
  /** Default wallet stamp for every captured error. */
  wallet?: WalletKind;
  /** Default chain info. Solana clusters use string ids ('mainnet-beta', 'devnet'). */
  chain?: ChainInfo;
  /** Per-call context override — receives the method name + raw args. */
  enrichContext?: (method: string, args: readonly unknown[]) => Partial<Web3ErrorContext>;
}

export function wrapSolanaConnection<T extends object>(
  connection: T,
  options: WrapSolanaConnectionOptions = {},
): T {
  return new Proxy(connection, {
    get(target, prop, receiver) {
      const value = Reflect.get(target, prop, receiver);
      if (typeof value !== 'function' || typeof prop !== 'string') return value;
      if (!TRACKED_METHODS.has(prop)) return value;

      return async (...args: unknown[]) => {
        try {
          return await Reflect.apply(value, target, args);
        } catch (error) {
          const ctx: Web3ErrorContext = {
            wallet: options.wallet,
            chain: options.chain,
            functionName: prop,
            ...(options.enrichContext?.(prop, args) ?? {}),
          };
          captureWeb3Error(error, ctx);
          throw error;
        }
      };
    },
  });
}
