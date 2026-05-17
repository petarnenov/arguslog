/**
 * Contract tests for the `useI18n` hook.
 *
 * Three guarantees worth pinning:
 *
 * 1. When `chrome.i18n` is missing (jsdom test env, content-script main world, etc.)
 *    `t(key)` returns the key string. The hook is a no-op in those contexts — UI gets
 *    English-looking placeholders (`navIssues`) so screenshots / bug reports surface
 *    the failure rather than rendering blank labels.
 *
 * 2. When `chrome.i18n.getMessage` returns the empty string (Chrome's "unknown key"
 *    signal) the hook still returns the key string instead. Pre-fix this would silently
 *    blank a NavLink; post-fix it's loud.
 *
 * 3. When `chrome.i18n.getMessage` resolves to a real string, that string passes
 *    through unchanged.
 *
 * The contract is the only thing this test verifies — exhaustive key coverage belongs
 * in a separate JSON-vs-TypeScript drift test, not here.
 */
import { renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { useI18n } from '../../src/shared/hooks/useI18n';

interface FakeI18n {
  getMessage: (key: string, substitutions?: string | string[]) => string;
}

const ORIGINAL_CHROME = (globalThis as { chrome?: { i18n?: FakeI18n } }).chrome;

function setChromeI18n(impl: FakeI18n | undefined): void {
  const target = globalThis as { chrome?: { i18n?: FakeI18n } };
  if (impl === undefined) {
    delete target.chrome;
    return;
  }
  target.chrome = { i18n: impl };
}

describe('useI18n', () => {
  beforeEach(() => {
    setChromeI18n(undefined);
  });

  afterEach(() => {
    if (ORIGINAL_CHROME) {
      (globalThis as { chrome?: typeof ORIGINAL_CHROME }).chrome = ORIGINAL_CHROME;
    } else {
      delete (globalThis as { chrome?: unknown }).chrome;
    }
  });

  it('returns the key as a placeholder when chrome.i18n is missing', () => {
    const { result } = renderHook(() => useI18n());
    expect(result.current.t('navIssues')).toBe('navIssues');
  });

  it('returns the key as a placeholder when chrome.i18n.getMessage returns the empty string (unknown key)', () => {
    setChromeI18n({ getMessage: () => '' });
    const { result } = renderHook(() => useI18n());
    expect(result.current.t('navIssues')).toBe('navIssues');
  });

  it('passes a real translation through unchanged', () => {
    setChromeI18n({ getMessage: (key) => (key === 'navIssues' ? 'Проблеми' : '') });
    const { result } = renderHook(() => useI18n());
    expect(result.current.t('navIssues')).toBe('Проблеми');
  });

  it('forwards substitutions to chrome.i18n.getMessage verbatim', () => {
    const getMessage = vi.fn().mockReturnValue('Hello, John');
    setChromeI18n({ getMessage });

    const { result } = renderHook(() => useI18n());
    result.current.t('navIssues', ['John']);

    // chrome.i18n.getMessage signature is (key, substitutions). Verifies we don't
    // accidentally swallow or stringify the substitutions array.
    expect(getMessage).toHaveBeenCalledWith('navIssues', ['John']);
  });
});
