/**
 * @arguslog/sdk-web3 — rich EVM transaction error capture for viem + ethers v6.
 *
 * <p>Plug this on top of {@code @arguslog/sdk-browser}. When a wallet write reverts you no
 * longer see "Error: Transaction failed" — you see the chain, the wallet, the contract, the
 * function name + args, and the decoded revert reason ({@code ERC20InsufficientBalance(0xUser,
 * 50, 100)} not a hex blob).
 */
export { captureWeb3Error } from './capture-web3-error.js';
export { decodeViemError } from './decode-viem-error.js';
export { decodeEthersError } from './decode-ethers-error.js';
export { installProviderBreadcrumbs, detectWallet, type Eip1193Provider } from './eip-1193.js';
export { wrapWalletClient, type WrapWalletClientOptions } from './wrap-wallet-client.js';
export type {
  ChainInfo,
  DecodedWeb3Error,
  WalletKind,
  Web3ErrorContext,
  Web3ErrorKind,
} from './types.js';

import { installProviderBreadcrumbs, detectWallet, type Eip1193Provider } from './eip-1193.js';
import { wrapWalletClient, type WrapWalletClientOptions } from './wrap-wallet-client.js';

/**
 * Convenience init that wires both pieces in one call. Pass an EIP-1193 provider
 * (e.g. {@code window.ethereum} or the result of {@code @walletconnect/ethereum-provider})
 * and / or a viem {@code WalletClient}; the function returns the wrapped client (when
 * supplied) and an uninstaller for the provider listeners.
 */
export function initWeb3<T extends object>(opts: {
  provider?: Eip1193Provider | null;
  walletClient?: T;
  wrapOptions?: WrapWalletClientOptions;
}): { walletClient: T | undefined; uninstall: () => void } {
  const uninstallProvider = installProviderBreadcrumbs(opts.provider ?? null);
  const wallet = opts.provider ? detectWallet(opts.provider) : undefined;
  const wrapOptions: WrapWalletClientOptions = {
    ...(opts.wrapOptions ?? {}),
    wallet: opts.wrapOptions?.wallet ?? wallet,
  };
  const walletClient = opts.walletClient
    ? wrapWalletClient(opts.walletClient, wrapOptions)
    : undefined;
  return {
    walletClient,
    uninstall: () => {
      uninstallProvider();
    },
  };
}
