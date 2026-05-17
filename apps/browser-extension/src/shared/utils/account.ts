import type { AccountSummary } from '../validation/models';

export function getAccountLabel(accountSummary: AccountSummary | undefined): string {
  if (!accountSummary) {
    return 'Disconnected';
  }

  return accountSummary.displayName ?? accountSummary.email ?? accountSummary.userId ?? 'Connected';
}
