/**
 * Maps {@code web3.chain} tag values to block-explorer base URLs. Used by {@link
 * Web3Panel} to turn contract addresses + transaction hashes into clickable links so a
 * support engineer can hop straight from the dashboard to the on-chain trace.
 *
 * <p>Identification key: the {@code chain.id} we get on the breadcrumb / tag is either
 * a numeric EVM chain id ({@code 1}, {@code 8453}, …) or a Solana cluster string
 * ({@code 'mainnet-beta'}, {@code 'devnet'}, {@code 'testnet'}). Unknown ids fall back
 * to plain text rendering.
 */
interface ExplorerConfig {
  /** Display name shown in the chain badge. */
  name: string;
  /** Function that turns a contract / account address into an explorer URL. */
  address: (addr: string) => string;
  /** Function that turns a transaction hash / signature into an explorer URL. */
  tx: (hash: string) => string;
}

const EVM_EXPLORERS: Record<number, ExplorerConfig> = {
  1: {
    name: 'Ethereum mainnet',
    address: (a) => `https://etherscan.io/address/${a}`,
    tx: (h) => `https://etherscan.io/tx/${h}`,
  },
  10: {
    name: 'Optimism',
    address: (a) => `https://optimistic.etherscan.io/address/${a}`,
    tx: (h) => `https://optimistic.etherscan.io/tx/${h}`,
  },
  56: {
    name: 'BNB Chain',
    address: (a) => `https://bscscan.com/address/${a}`,
    tx: (h) => `https://bscscan.com/tx/${h}`,
  },
  137: {
    name: 'Polygon',
    address: (a) => `https://polygonscan.com/address/${a}`,
    tx: (h) => `https://polygonscan.com/tx/${h}`,
  },
  8453: {
    name: 'Base',
    address: (a) => `https://basescan.org/address/${a}`,
    tx: (h) => `https://basescan.org/tx/${h}`,
  },
  42161: {
    name: 'Arbitrum One',
    address: (a) => `https://arbiscan.io/address/${a}`,
    tx: (h) => `https://arbiscan.io/tx/${h}`,
  },
  43114: {
    name: 'Avalanche',
    address: (a) => `https://snowtrace.io/address/${a}`,
    tx: (h) => `https://snowtrace.io/tx/${h}`,
  },
  // Test networks — useful in dev-mode events.
  11155111: {
    name: 'Sepolia',
    address: (a) => `https://sepolia.etherscan.io/address/${a}`,
    tx: (h) => `https://sepolia.etherscan.io/tx/${h}`,
  },
  84532: {
    name: 'Base Sepolia',
    address: (a) => `https://sepolia.basescan.org/address/${a}`,
    tx: (h) => `https://sepolia.basescan.org/tx/${h}`,
  },
};

const SOLANA_CLUSTERS: Record<string, ExplorerConfig> = {
  'mainnet-beta': {
    name: 'Solana mainnet',
    address: (a) => `https://solscan.io/account/${a}`,
    tx: (h) => `https://solscan.io/tx/${h}`,
  },
  devnet: {
    name: 'Solana devnet',
    address: (a) => `https://solscan.io/account/${a}?cluster=devnet`,
    tx: (h) => `https://solscan.io/tx/${h}?cluster=devnet`,
  },
  testnet: {
    name: 'Solana testnet',
    address: (a) => `https://solscan.io/account/${a}?cluster=testnet`,
    tx: (h) => `https://solscan.io/tx/${h}?cluster=testnet`,
  },
};

export function explorerForChain(chainId: string | number | undefined): ExplorerConfig | undefined {
  if (chainId === undefined) return undefined;
  if (typeof chainId === 'number') return EVM_EXPLORERS[chainId];
  // Solana cluster names are strings; numeric ids stored as strings should still map to EVM.
  if (/^\d+$/.test(chainId)) return EVM_EXPLORERS[Number.parseInt(chainId, 10)];
  return SOLANA_CLUSTERS[chainId];
}
