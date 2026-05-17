import { FEATURE_REQUIREMENTS } from '@arguslog/mcp-server/contract';

import type { CapabilitySnapshot } from '../validation/models';

function resolveRequiredTools(feature: string): string[] {
  if (feature in FEATURE_REQUIREMENTS.workflows) {
    return FEATURE_REQUIREMENTS.workflows[feature as keyof typeof FEATURE_REQUIREMENTS.workflows] ?? [];
  }

  const direct = FEATURE_REQUIREMENTS[feature as keyof typeof FEATURE_REQUIREMENTS];
  if (Array.isArray(direct)) {
    return direct;
  }

  return [];
}

export function getFeatureAvailability(
  snapshot: CapabilitySnapshot | undefined,
  feature: string,
): { available: boolean; missingTools: string[] } {
  const requiredTools = resolveRequiredTools(feature);
  const missingTools =
    snapshot === undefined ? requiredTools : requiredTools.filter((tool) => !snapshot.toolNames.includes(tool));

  return {
    available: missingTools.length === 0,
    missingTools,
  };
}
