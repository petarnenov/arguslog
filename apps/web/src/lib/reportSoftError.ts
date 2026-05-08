import { captureMessage, type Level } from '@arguslog/sdk-browser';
import { useEffect, useRef } from 'react';

/**
 * Emit an Arguslog event when the page renders an "anomalous" empty/fallback state — the kind of
 * condition that doesn't throw (so global handlers miss it) but represents an unexpected user
 * journey. Examples: URL slug doesn't match the user's orgs, projectId isn't a valid number, an
 * org disappeared between membership check and detail load.
 *
 * <p>Deduplicated by summary string within a component lifetime — re-renders on the same anomaly
 * fire once. Switching anomaly text fires again (so transitioning slugs report).
 */
export function useReportSoftError(active: boolean, summary: string, level: Level = 'warning') {
  const lastSentRef = useRef<string | null>(null);
  useEffect(() => {
    if (!active) {
      // Clear so re-entering the anomaly later still reports.
      lastSentRef.current = null;
      return;
    }
    if (lastSentRef.current === summary) return;
    lastSentRef.current = summary;
    captureMessage(summary, level);
  }, [active, summary, level]);
}
