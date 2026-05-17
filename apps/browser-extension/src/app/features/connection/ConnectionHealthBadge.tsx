/**
 * Reads the persisted connection-health snapshot (last success + last auth error) off the
 * existing `connection-status` React Query key and renders one of three states:
 *
 *   🟢 Connected — last success {relative time}
 *   🔴 Auth failed — {code}: {message} ({relative time})
 *   ⚪ Not connected yet
 *
 * Tie-breaker when both fields are set: whichever has the newer ISO timestamp wins. That
 * way, re-entering a valid PAT after a failure → next successful call → ✅; a fresh auth
 * failure during a stable session → 🔴.
 */
import { useQuery } from '@tanstack/react-query';

import { getConnectionStatus } from '../../../shared/domain/connection';

function relativeTime(iso: string, now: number = Date.now()): string {
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return 'unknown';
  const deltaSec = Math.round((now - then) / 1000);
  if (deltaSec < 5) return 'just now';
  if (deltaSec < 60) return `${deltaSec}s ago`;
  const deltaMin = Math.round(deltaSec / 60);
  if (deltaMin < 60) return `${deltaMin}m ago`;
  const deltaHr = Math.round(deltaMin / 60);
  if (deltaHr < 24) return `${deltaHr}h ago`;
  const deltaDay = Math.round(deltaHr / 24);
  return `${deltaDay}d ago`;
}

export function ConnectionHealthBadge() {
  const statusQuery = useQuery({
    queryKey: ['connection-status'],
    queryFn: getConnectionStatus,
  });

  const session = statusQuery.data?.authSession;
  const lastConnectedAt = session?.lastConnectedAt;
  const lastAuthError = session?.lastAuthError;

  // Tie-breaker: most recent event wins. If lastAuthError exists AND is newer than (or
  // equal to) lastConnectedAt, render the error state. Otherwise prefer the success.
  const errorIsCurrent =
    lastAuthError &&
    (!lastConnectedAt ||
      new Date(lastAuthError.occurredAt).getTime() >= new Date(lastConnectedAt).getTime());

  if (errorIsCurrent && lastAuthError) {
    return (
      <div
        data-testid="connection-health-badge"
        data-state="auth-failed"
        className="rounded-lg border border-red-700/60 bg-red-900/20 p-3 text-sm"
      >
        <div className="flex items-center gap-2 font-medium text-red-300">
          <span aria-hidden="true">🔴</span>
          Auth failed — {lastAuthError.code}
          {lastAuthError.httpStatus ? ` (HTTP ${lastAuthError.httpStatus})` : ''}
        </div>
        <p className="mt-1 text-xs text-red-200/80">
          {lastAuthError.message} · {relativeTime(lastAuthError.occurredAt)}
        </p>
      </div>
    );
  }

  if (lastConnectedAt) {
    return (
      <div
        data-testid="connection-health-badge"
        data-state="connected"
        className="rounded-lg border border-emerald-700/60 bg-emerald-900/20 p-3 text-sm"
      >
        <div className="flex items-center gap-2 font-medium text-emerald-300">
          <span aria-hidden="true">🟢</span>
          Connected — last success {relativeTime(lastConnectedAt)}
        </div>
      </div>
    );
  }

  return (
    <div
      data-testid="connection-health-badge"
      data-state="not-connected"
      className="rounded-lg border border-slate-700/60 bg-slate-900/40 p-3 text-sm text-slate-400"
    >
      <span aria-hidden="true">⚪</span> Not connected yet — enter a PAT to test.
    </div>
  );
}
