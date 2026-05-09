import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const { addBreadcrumb, captureException } = vi.hoisted(() => ({
  addBreadcrumb: vi.fn(),
  captureException: vi.fn(() => 'evt-1'),
}));
vi.mock('@arguslog/sdk-browser', () => ({ addBreadcrumb, captureException }));

import { wrapSolanaConnection } from '../wrap-solana-connection.js';

describe('wrapSolanaConnection', () => {
  beforeEach(() => {
    addBreadcrumb.mockReset();
    captureException.mockReset().mockReturnValue('evt-1');
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('passes through successful sendTransaction calls untouched', async () => {
    const conn = {
      sendTransaction: vi.fn(async (_tx: unknown) => 'sigOK'),
    };
    const wrapped = wrapSolanaConnection(conn);
    const out = await wrapped.sendTransaction({ instructions: [] });
    expect(out).toBe('sigOK');
    expect(captureException).not.toHaveBeenCalled();
  });

  it('captures sendTransaction errors with chain + wallet stamps and re-throws', async () => {
    const error = {
      name: 'SendTransactionError',
      logs: [
        'Program ABC invoke [1]',
        'Program log: AnchorError caused by account: pool. Error Code: SlippageExceeded. Error Number: 6010. Error Message: Slippage tolerance exceeded.',
      ],
      signature: 'sig123',
    };
    const conn = {
      sendTransaction: vi.fn(async (_tx: unknown) => {
        throw error;
      }),
    };
    const wrapped = wrapSolanaConnection(conn, {
      wallet: 'phantom',
      chain: { id: 'mainnet-beta', name: 'Solana mainnet' },
    });
    await expect(wrapped.sendTransaction({})).rejects.toBe(error);
    expect(addBreadcrumb).toHaveBeenCalledWith(
      expect.objectContaining({
        data: expect.objectContaining({
          wallet: 'phantom',
          chain: { id: 'mainnet-beta', name: 'Solana mainnet' },
          functionName: 'sendTransaction',
          errorCode: 'SlippageExceeded',
        }),
      }),
    );
  });

  it('captures simulateTransaction errors', async () => {
    const conn = {
      simulateTransaction: vi.fn(async (_tx: unknown) => {
        throw new Error('Simulation failed: AccountNotFound');
      }),
    };
    const wrapped = wrapSolanaConnection(conn);
    await expect(wrapped.simulateTransaction({})).rejects.toThrow();
    expect(addBreadcrumb).toHaveBeenCalledWith(
      expect.objectContaining({
        data: expect.objectContaining({ functionName: 'simulateTransaction' }),
      }),
    );
  });

  it('captures confirmTransaction errors (blockhash expired)', async () => {
    const conn = {
      confirmTransaction: vi.fn(async (_strategy: unknown) => {
        throw {
          name: 'TransactionExpiredBlockheightExceededError',
          message: 'block height exceeded',
        };
      }),
    };
    const wrapped = wrapSolanaConnection(conn);
    await expect(wrapped.confirmTransaction({})).rejects.toBeDefined();
    expect(addBreadcrumb).toHaveBeenCalledWith(
      expect.objectContaining({
        data: expect.objectContaining({ kind: 'solana.blockhashExpired' }),
      }),
    );
  });

  it('does NOT wrap untracked methods (read calls)', async () => {
    const getBalance = vi.fn(async (_pk: unknown) => {
      throw new Error('rpc fail');
    });
    const conn = { getBalance };
    const wrapped = wrapSolanaConnection(conn);
    await expect(wrapped.getBalance('pk')).rejects.toThrow();
    expect(captureException).not.toHaveBeenCalled();
  });
});
