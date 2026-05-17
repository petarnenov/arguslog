/**
 * Thin React hook around `getFeatureAvailability` — every gated button / panel in the
 * extension reads from this so a future change (e.g. fold PAT scopes alongside tool
 * names in the availability decision, hot-swap to a different snapshot source) ripples
 * through one file instead of every consumer.
 *
 * The connection-status query is already populated everywhere else in the extension;
 * this hook just narrows the slice + delegates.
 */
import { useQuery } from '@tanstack/react-query';

import { getConnectionStatus } from '../domain/connection';
import { getFeatureAvailability } from '../mcp/capability-registry';

export function useFeatureAvailability(feature: string) {
  const { data } = useQuery({
    queryKey: ['connection-status'],
    queryFn: getConnectionStatus,
  });
  return getFeatureAvailability(data?.capabilitySnapshot, feature);
}
