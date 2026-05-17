import '@testing-library/jest-dom/vitest';

import enMessages from '../../public/_locales/en/messages.json';

/**
 * Provide a working `chrome.i18n.getMessage` in the jsdom test environment so any
 * component rendering via `useI18n()` sees the real English strings, not the key
 * placeholders. The hook's `chrome.i18n is missing` fallback (key string verbatim) is
 * exercised independently in `i18n.test.tsx`, which deliberately deletes the global
 * before each test — so the contract test for that behaviour still works.
 *
 * Substitutions are applied with chrome.i18n's `$1` / `$2` / … placeholder grammar so
 * substitution-using callers behave realistically too.
 */
type MessagesShape = Record<string, { message: string }>;

function applySubstitutions(message: string, substitutions?: string | string[]): string {
  if (!substitutions) return message;
  const subs = Array.isArray(substitutions) ? substitutions : [substitutions];
  return message.replace(/\$(\d+)/g, (_, index) => subs[Number(index) - 1] ?? '');
}

const messagesByKey = enMessages as MessagesShape;

(globalThis as { chrome?: unknown }).chrome = {
  i18n: {
    getMessage: (key: string, substitutions?: string | string[]): string => {
      const entry = messagesByKey[key];
      if (!entry) return '';
      return applySubstitutions(entry.message, substitutions);
    },
  },
};
