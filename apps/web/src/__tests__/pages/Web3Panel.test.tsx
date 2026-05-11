import { MantineProvider } from '@mantine/core';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';

import '../../i18n';
import type { RawBreadcrumb } from '../../pages/issue-detail/Breadcrumbs';
import type { EventMeta } from '../../pages/issue-detail/EventDetails';
import { extractWeb3Summary, Web3Panel } from '../../pages/issue-detail/Web3Panel';

function meta(tags: Record<string, string> = {}): EventMeta {
  return { tags, contexts: {}, extra: {} };
}

function bc(category: string, data: Record<string, unknown> = {}): RawBreadcrumb {
  return { timestamp: 0, category, message: '', level: 'error', data };
}

function renderPanel(node: React.ReactElement) {
  return render(<MantineProvider>{node}</MantineProvider>);
}

describe('extractWeb3Summary', () => {
  it('returns undefined when no web3 tag and no web3.error breadcrumb', () => {
    expect(extractWeb3Summary(meta(), [])).toBeUndefined();
    expect(extractWeb3Summary(meta(), [bc('console')])).toBeUndefined();
  });

  it('builds summary from tags when no breadcrumb', () => {
    const out = extractWeb3Summary(
      meta({ 'web3.kind': 'contract.reverted', 'web3.chain': '1', 'web3.wallet': 'metamask' }),
      [],
    );
    expect(out?.kind).toBe('contract.reverted');
    expect(out?.chain).toEqual({ id: 1 });
    expect(out?.wallet).toBe('metamask');
  });

  it('merges breadcrumb data over the tag-only summary', () => {
    const out = extractWeb3Summary(meta({ 'web3.kind': 'contract.reverted' }), [
      bc('web3.error', {
        kind: 'contract.reverted',
        errorName: 'ERC20InsufficientBalance',
        functionName: 'transfer',
        contract: '0xA0b8',
        args: ['0xRecipient', '100000000'],
        chain: { id: 1, name: 'Ethereum mainnet' },
      }),
    ]);
    expect(out?.errorName).toBe('ERC20InsufficientBalance');
    expect(out?.contract).toBe('0xA0b8');
    expect(out?.functionName).toBe('transfer');
    expect(out?.chain).toEqual({ id: 1, name: 'Ethereum mainnet' });
  });

  it('extracts Anchor + Solana program log fields', () => {
    const out = extractWeb3Summary(meta({ 'web3.kind': 'solana.anchorError' }), [
      bc('web3.error', {
        errorCode: 'SlippageExceeded',
        errorNumber: 6010,
        errorMessage: 'Slippage tolerance exceeded',
        origin: 'pool',
        signature: 'sig123',
        logs: ['Program ABC invoke [1]', 'Program log: AnchorError caused by …'],
        programId: 'ABC123',
      }),
    ]);
    expect(out?.anchorErrorCode).toBe('SlippageExceeded');
    expect(out?.anchorErrorNumber).toBe(6010);
    expect(out?.anchorOrigin).toBe('pool');
    expect(out?.signature).toBe('sig123');
    expect(out?.logs?.length).toBe(2);
    expect(out?.programId).toBe('ABC123');
  });

  it('uses the latest web3.error breadcrumb when several are present', () => {
    const out = extractWeb3Summary(meta({ 'web3.kind': 'contract.reverted' }), [
      bc('web3.error', { errorName: 'OldError' }),
      bc('console', { x: 1 }),
      bc('web3.error', { errorName: 'NewError' }),
    ]);
    expect(out?.errorName).toBe('NewError');
  });
});

describe('Web3Panel', () => {
  it('renders kind + chain + wallet badges', () => {
    renderPanel(
      <Web3Panel
        summary={{
          kind: 'contract.reverted',
          chain: { id: 1, name: 'Ethereum mainnet' },
          wallet: 'metamask',
          source: 'viem',
        }}
      />,
    );
    expect(screen.getByTestId('web3-kind-badge')).toHaveTextContent('contract.reverted');
    expect(screen.getByTestId('web3-panel')).toHaveTextContent('Ethereum mainnet');
    expect(screen.getByTestId('web3-panel')).toHaveTextContent('metamask');
    expect(screen.getByTestId('web3-panel')).toHaveTextContent('via viem');
  });

  it('shows the headline as Anchor "code: message" when both present', () => {
    renderPanel(
      <Web3Panel
        summary={{
          kind: 'solana.anchorError',
          anchorErrorCode: 'SlippageExceeded',
          anchorErrorMessage: 'Slippage tolerance exceeded',
        }}
      />,
    );
    expect(screen.getByTestId('web3-headline')).toHaveTextContent('SlippageExceeded');
    expect(screen.getByTestId('web3-headline')).toHaveTextContent('Slippage tolerance exceeded');
  });

  it('shows EVM headline as errorName + reason', () => {
    renderPanel(
      <Web3Panel
        summary={{
          kind: 'contract.reverted',
          errorName: 'ERC20InsufficientBalance',
          errorReason: 'token holder lacks balance',
        }}
      />,
    );
    expect(screen.getByTestId('web3-headline')).toHaveTextContent('ERC20InsufficientBalance');
  });

  it('renders a clickable etherscan link for an Ethereum mainnet contract', () => {
    renderPanel(
      <Web3Panel summary={{ kind: 'contract.reverted', chain: { id: 1 }, contract: '0xABCDEF' }} />,
    );
    const link = screen.getByTestId('web3-contract').querySelector('a') as HTMLAnchorElement | null;
    expect(link?.href).toBe('https://etherscan.io/address/0xABCDEF');
    expect(link?.target).toBe('_blank');
  });

  it('renders a solscan link for a Solana mainnet signature', () => {
    renderPanel(
      <Web3Panel
        summary={{
          kind: 'solana.anchorError',
          chain: { id: 'mainnet-beta' },
          signature: 'sig123',
        }}
      />,
    );
    const link = screen
      .getByTestId('web3-signature')
      .querySelector('a') as HTMLAnchorElement | null;
    expect(link?.href).toBe('https://solscan.io/tx/sig123');
  });

  it('omits explorer link when chain is unknown — value still rendered as code', () => {
    renderPanel(
      <Web3Panel
        summary={{ kind: 'contract.reverted', chain: { id: 99999 }, contract: '0xABC' }}
      />,
    );
    const link = screen.getByTestId('web3-contract').querySelector('a') as HTMLAnchorElement | null;
    expect(link).toBeNull();
    expect(screen.getByTestId('web3-contract')).toHaveTextContent('0xABC');
  });

  it('shows program logs collapsibly', async () => {
    renderPanel(
      <Web3Panel
        summary={{
          kind: 'solana.programError',
          logs: ['Program ABC invoke [1]', 'Program ABC failed: custom program error: 0x1'],
        }}
      />,
    );
    const trigger = screen
      .getByTestId('web3-program-logs')
      .querySelector('button') as HTMLButtonElement;
    expect(trigger).toBeInTheDocument();
    await userEvent.click(trigger);
    expect(screen.getByTestId('web3-program-logs')).toHaveTextContent('Program ABC invoke [1]');
  });

  it('hides empty sections (no args, no logs, no compared values)', () => {
    renderPanel(<Web3Panel summary={{ kind: 'user.rejected' }} />);
    expect(screen.queryByTestId('web3-args')).not.toBeInTheDocument();
    expect(screen.queryByTestId('web3-program-logs')).not.toBeInTheDocument();
    expect(screen.queryByTestId('web3-compared-values')).not.toBeInTheDocument();
  });
});
