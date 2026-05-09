# @arguslog/sdk-web3

[![npm version](https://img.shields.io/npm/v/@arguslog/sdk-web3.svg)](https://www.npmjs.com/package/@arguslog/sdk-web3)
[![license](https://img.shields.io/npm/l/@arguslog/sdk-web3.svg)](https://github.com/petarnenov/arguslog/blob/main/LICENSE)

> Sentry sees `Error: transaction failed`. Arguslog tells you **why**: the chain, the wallet,
> the contract, the function, the args, and the **decoded** revert reason. Built on top of
> `@arguslog/sdk-browser`. Native support for [viem](https://viem.sh) and
> [ethers v6](https://docs.ethers.org/v6/).

```text
ContractFunctionRevertedError: ERC20InsufficientBalance

  Chain:    1 (Ethereum mainnet)
  Wallet:   MetaMask via window.ethereum
  Contract: 0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48 (USDC)
  Function: transfer(address, uint256)
  Args:     [0xRecipient…, 100000000]   // 100 USDC

  Revert decoded:
    errorName:  ERC20InsufficientBalance
    sender:     0xUser…
    balance:    50000000     // 50 USDC
    needed:     100000000    // 100 USDC

  Breadcrumbs leading up:
    [12:30:01] web3.wallet  connected MetaMask, chainId=1
    [12:30:05] ui.click     button "Send 100 USDC"
    [12:30:06] web3.error   Reverted: ERC20InsufficientBalance
```

## Install

```bash
pnpm add @arguslog/sdk-browser @arguslog/sdk-web3
# plus whichever wallet client you use:
pnpm add viem
# or
pnpm add ethers
```

`viem` and `ethers` are **optional peer dependencies** — install only the one you actually
use. The decoder works on either; the auto-wrap helper currently targets viem's
`WalletClient` API.

## Quick start

```ts
import { init } from '@arguslog/sdk-browser';
import { initWeb3 } from '@arguslog/sdk-web3';
import { createWalletClient, custom } from 'viem';
import { mainnet } from 'viem/chains';

// 1. Standard browser SDK init.
init({
  dsn: 'arguslog://<publicKey>@<host>/api/<projectId>',
  integrations: ['globalHandlers', 'autoBreadcrumbs'],
});

// 2. Wrap your wallet client + listen to provider events.
const rawClient = createWalletClient({
  chain: mainnet,
  transport: custom(window.ethereum),
});

const { walletClient } = initWeb3({
  provider: window.ethereum,
  walletClient: rawClient,
});

// 3. Use walletClient as usual — every error is auto-captured with full context.
await walletClient!.writeContract({
  address: '0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48',
  abi: usdcAbi,
  functionName: 'transfer',
  args: ['0xRecipient', 100_000000n],
});
```

## Manual capture (ethers, raw provider, etc.)

When you can't / don't want to wrap a client, capture errors yourself in a `try/catch`:

```ts
import { captureWeb3Error } from '@arguslog/sdk-web3';

try {
  await contract.transfer(recipient, amount);
} catch (err) {
  captureWeb3Error(err, {
    chain: { id: 1, name: 'Ethereum mainnet' },
    wallet: 'metamask',
    contract: contract.target as string,
    functionName: 'transfer',
    args: [recipient, amount],
  });
  throw err; // up to you whether to swallow
}
```

The decoder runs viem first (richer), falls back to ethers v6 (`.code` switch), and a
generic `Error.message` shape last. Whatever it can extract goes onto the captured event as
**tags** (searchable: `web3.kind`, `web3.chain`, `web3.wallet`, `web3.contract`) and as a
rich **breadcrumb** (the full structured data).

## What `initWeb3` actually wires

| Wired thing                   | Effect                                                                                                                |
| ----------------------------- | --------------------------------------------------------------------------------------------------------------------- |
| EIP-1193 `accountsChanged`    | Breadcrumb with the (truncated) new address. Empty array → "wallet disconnected" at warning level.                    |
| EIP-1193 `chainChanged`       | Breadcrumb with hex + decoded decimal chain id. Sentry-style "you switched mid-flow" detection.                       |
| EIP-1193 `connect`/`disconnect` | Breadcrumbs for session boundaries.                                                                                  |
| `writeContract` wrap          | Try / catch around the call; on throw → `captureWeb3Error` with `{ contract, functionName, args }` extracted.         |
| `sendTransaction` wrap        | Same shape, with `to` exposed as `contract`.                                                                          |
| `signMessage` / `signTypedData` / `signTransaction` / `deployContract` / `prepareTransactionRequest` wraps | Each tracked, errors captured with the method name as `functionName`. |

Read methods (`getBalance`, `getBlock`, etc.) pass through unchanged — RPC calls are already
breadcrumbed by the `fetch` integration in `@arguslog/sdk-browser`.

## Decoded error kinds

`captureWeb3Error` emits a stable `web3.kind` tag the dashboard can group on:

| Kind                          | Source mapping                                                                              |
| ----------------------------- | ------------------------------------------------------------------------------------------- |
| `user.rejected`               | viem `UserRejectedRequestError`, ethers `ACTION_REJECTED`, viem `TransactionRejectedRpcError` |
| `wallet.notConnected`         | (reserved — emitted by Phase 3 wagmi adapter)                                              |
| `chain.mismatch`              | viem `ChainMismatchError`                                                                  |
| `contract.reverted`           | viem `ContractFunctionRevertedError` (with `errorName` + `args`), ethers `CALL_EXCEPTION`  |
| `tx.executionFailed`          | viem `TransactionExecutionError`                                                           |
| `tx.replacementUnderpriced`   | ethers `REPLACEMENT_UNDERPRICED`                                                           |
| `tx.nonceExpired`             | viem `NonceTooLowError` / `NonceTooHighError`, ethers `NONCE_EXPIRED`                      |
| `tx.insufficientFunds`        | viem `InsufficientFundsError`, ethers `INSUFFICIENT_FUNDS`                                 |
| `gas.estimateFailed`          | viem `EstimateGasExecutionError`, ethers `UNPREDICTABLE_GAS_LIMIT`                         |
| `rpc.rateLimit`               | viem `RpcRequestError` / `HttpRequestError` with status 429                                |
| `rpc.timeout`                 | viem `RpcRequestError` / `HttpRequestError`, ethers `NETWORK_ERROR` / `TIMEOUT` / `SERVER_ERROR` |
| `rpc.invalidParams`           | viem `InvalidParamsRpcError`                                                               |
| `unknown`                     | Anything we couldn't map — original error name / code preserved on the payload.            |

## Roadmap

- **Phase 2**: Solana support via `@solana/web3.js` (program logs decoding, simulation logs).
- **Phase 3**: `wagmi` v2 React hooks wrapper, WalletConnect session lifecycle breadcrumbs.
- **Phase 4**: Server-side ABI upload → automatic decoding of custom-error reverts the
  client-side ABI didn't include.

## License

MIT — see [LICENSE](https://github.com/petarnenov/arguslog/blob/main/LICENSE).
