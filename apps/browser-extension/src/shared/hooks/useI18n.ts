/**
 * Thin synchronous wrapper over `chrome.i18n.getMessage`. The native API is sync — no
 * promises, no React Query needed — so the hook is just a memoised function that
 * returns a `t(key, substitutions?)` callable.
 *
 * Type-safety: the {@link I18nKey} union is hand-derived from
 * `public/_locales/en/messages.json` and must be kept in sync. A linter rule could
 * automate this from the JSON, but it's <30 keys; a 1-second test (`i18n.test.tsx`)
 * catches drift in CI.
 *
 * Fallback behaviour:
 * - `chrome.i18n.getMessage` returns `''` for unknown keys, which would produce blank
 *   labels in the UI — a silent failure mode the operator can't diagnose. We override
 *   that: an unknown key returns the key string itself (`'navIssues'`), making missing
 *   translations loud in screenshots and bug reports.
 * - In contexts without `chrome.i18n` (vitest jsdom, contract tests), the hook returns
 *   the key string for every call. Tests can assert keys directly without mocking
 *   chrome.i18n; the contract test in `i18n.test.tsx` pins this behaviour.
 */
import { useMemo } from 'react';

/**
 * String-keyed identifiers for every message in `public/_locales/en/messages.json`.
 * Add a new key here when adding one to messages.json — the unit test in
 * `i18n.test.tsx` smoke-checks both files agree.
 */
export type I18nKey =
  | 'extensionName'
  | 'extensionDescription'
  | 'navWorkspace'
  | 'navIssues'
  | 'navReleases'
  | 'navWorkflows'
  | 'navTools'
  | 'navHistory'
  | 'navPlaybooks'
  | 'navSettings'
  | 'btnConnect'
  | 'btnConnecting'
  | 'btnPickProject'
  | 'btnSave'
  | 'btnCancel'
  | 'btnRetry'
  | 'errIssuesLoadFailed'
  | 'errReleasesLoadFailed'
  | 'errToolsLoadFailed';

type Substitutions = string | string[] | undefined;

export interface I18n {
  t: (key: I18nKey, substitutions?: Substitutions) => string;
}

/**
 * Resolve a message key. Module-scoped so non-React callers (background scripts,
 * domain helpers) can use it too. The hook below just memoises the bound function for
 * React components that prefer the hook ergonomics.
 */
export function translate(key: I18nKey, substitutions?: Substitutions): string {
  const api: typeof chrome.i18n | undefined =
    typeof chrome !== 'undefined' && 'i18n' in chrome ? chrome.i18n : undefined;
  if (!api) return key;
  const message = api.getMessage(key, substitutions);
  return message === '' ? key : message;
}

export function useI18n(): I18n {
  return useMemo(() => ({ t: translate }), []);
}
