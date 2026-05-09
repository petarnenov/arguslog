# @arguslog/sdk-web3

[![npm version](https://img.shields.io/npm/v/@arguslog/sdk-web3.svg)](https://www.npmjs.com/package/@arguslog/sdk-web3)
[![license](https://img.shields.io/npm/l/@arguslog/sdk-web3.svg)](https://github.com/petarnenov/arguslog/blob/main/LICENSE)

> Sentry sees `Error: transaction failed`. Arguslog tells you **why**: the chain, the wallet,
> the contract / program, the function / instruction, the args, and the **decoded** revert
> reason. Built on top of `@arguslog/sdk-browser`. Native support for
> [viem](https://viem.sh), [ethers v6](https://docs.ethers.org/v6/),
> [@solana/web3.js](https://solana-labs.github.io/solana-web3.js/) and
> [Anchor](https://www.anchor-lang.com/).

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
# plus whichever client(s) you use:
pnpm add viem                # EVM, modern
pnpm add ethers              # EVM, legacy
pnpm add @solana/web3.js     # Solana
pnpm add @coral-xyz/anchor   # Solana, Anchor framework (optional)
```

All four are **optional peer dependencies** — install only what you actually use. The
decoder operates on duck-typed objects, so it never imports any of them at runtime; you
can mix EVM and Solana in the same app and both error sources will round-trip through
`captureWeb3Error`. The auto-wrap helper currently targets viem's `WalletClient` API
(EVM); Solana support today is via `captureWeb3Error()` in your own `try/catch`.

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

## Manual capture (ethers, Solana, raw provider, …)

When you can't / don't want to wrap a client, capture errors yourself in a `try/catch`:

```ts
import { captureWeb3Error } from '@arguslog/sdk-web3';

// EVM example with ethers v6
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
  throw err;
}

// Solana example with @solana/web3.js + Anchor
try {
  await program.methods.swapTokens(amountIn, minOut).rpc();
} catch (err) {
  captureWeb3Error(err, {
    chain: { id: 'mainnet-beta', name: 'Solana mainnet' },
    wallet: 'phantom',
    contract: program.programId.toBase58(),
    functionName: 'swapTokens',
    args: [amountIn, minOut],
  });
  throw err;
}
```

The decoder runs in this order: **viem** (richest typed errors) → **ethers v6** (`.code`
field) → **Solana** (Anchor / wallet adapter / log parser) → generic `Error.message`.
Whatever it extracts goes onto the captured event as **tags** (searchable: `web3.kind`,
`web3.chain`, `web3.wallet`, `web3.contract`) and as a rich **breadcrumb** (the full
structured data).

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
| `solana.programError`         | Solana custom program errors decoded from logs (`Program X failed: custom program error: 0xN`), `InstructionError` JSON-RPC variants, generic non-Anchor program failures. |
| `solana.anchorError`          | `@coral-xyz/anchor` `AnchorError` — both the typed object (`_isAnchorError: true`) AND the parsed log line (`Program log: AnchorError caused by account: X. Error Code: …`). Carries `errorCode`, `errorNumber`, `origin`, `errorMessage`, `comparedValues`. |
| `solana.simulationFailed`     | `Connection.simulateTransaction` preflight rejected the tx.                                |
| `solana.blockhashExpired`     | `TransactionExpiredBlockheightExceededError` — tx not landed in time, retryable.           |
| `solana.computeBudgetExceeded`| Compute units exceeded the per-tx budget.                                                  |
| `solana.insufficientLamports` | `InsufficientFundsForRent` or "insufficient lamports" message — account doesn't have enough SOL for rent or transfer. |
| `unknown`                     | Anything we couldn't map — original error name / code preserved on the payload.            |

## Roadmap

- **Phase 1** ✅ — viem + ethers v6 decoders, EIP-1193 provider events, `wrapWalletClient`.
- **Phase 2** ✅ — Solana support via `@solana/web3.js` + Anchor; wallet adapter errors;
  program-log parsing (Anchor + custom-error hex codes).
- **Phase 3** — `wagmi` v2 React hooks wrapper, WalletConnect session lifecycle breadcrumbs,
  Solana wallet adapter direct integration (auto-wrap `Connection.sendTransaction` etc.).
- **Phase 4** — server-side ABI / Anchor IDL upload → richer decoding of custom errors the
  client-side bundle didn't include.

## License

MIT — see [LICENSE](https://github.com/petarnenov/arguslog/blob/main/LICENSE).
