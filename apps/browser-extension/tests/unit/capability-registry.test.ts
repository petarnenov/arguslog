import { describe, expect, it } from 'vitest';

import { getFeatureAvailability } from '../../src/shared/mcp/capability-registry';

describe('getFeatureAvailability', () => {
  const snapshot = {
    serverVersion: '1.0.0',
    toolNames: ['get_me', 'list_my_orgs', 'list_projects', 'list_issues', 'get_issue'],
    promptIds: [],
    detectedScopes: ['authenticated'],
    fetchedAt: '2026-05-16T00:00:00.000Z',
  };

  it('returns available when all required tools are present', () => {
    expect(getFeatureAvailability(snapshot, 'workspace')).toEqual({
      available: true,
      missingTools: [],
    });
  });

  it('returns missing tools when capability snapshot is incomplete', () => {
    expect(getFeatureAvailability(snapshot, 'issueActions')).toEqual({
      available: false,
      missingTools: ['triage_issue', 'assign_issue'],
    });
  });
});
