/**
 * Tests for `useFeatureAvailability`. The hook is a thin wrapper around `getFeatureAvailability`
 * + the `connection-status` query — these cases ensure both ends behave: snapshot absent (caller
 * sees `available: false` + the full required tools as missing), snapshot complete, snapshot
 * partial, and the workflow-feature namespace path.
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { describe, expect, it, vi } from 'vitest';

import { getConnectionStatus } from '../../src/shared/domain/connection';
import { useFeatureAvailability } from '../../src/shared/hooks/useFeatureAvailability';
import type { ConnectionStatus } from '../../src/shared/validation/models';

vi.mock('../../src/shared/domain/connection', () => ({
  getConnectionStatus: vi.fn(),
}));

function wrapper({ children }: { children: ReactNode }) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

function mockSnapshot(toolNames: string[] | undefined) {
  vi.mocked(getConnectionStatus).mockResolvedValue({
    settings: {
      endpoint: 'https://mcp.arguslog.org/mcp',
      persistenceMode: 'session',
      debug: false,
      theme: 'system',
    },
    authSession: { patPresent: true, persistenceMode: 'session' },
    capabilitySnapshot: toolNames
      ? {
          serverVersion: '2.5.0',
          toolNames,
          promptIds: [],
          detectedScopes: [],
          fetchedAt: new Date().toISOString(),
        }
      : undefined,
    workspaceSelection: { recents: [] },
  } as ConnectionStatus);
}

describe('useFeatureAvailability', () => {
  it('returns unavailable + every required tool when the snapshot is absent', async () => {
    mockSnapshot(undefined);
    const { result } = renderHook(() => useFeatureAvailability('issueActions'), { wrapper });
    await waitFor(() => {
      expect(result.current.available).toBe(false);
    });
    // `issueActions` requires `triage_issue` + `assign_issue` per the contract.
    expect(result.current.missingTools).toEqual(
      expect.arrayContaining(['triage_issue', 'assign_issue']),
    );
  });

  it('returns available when every required tool is in the snapshot', async () => {
    mockSnapshot(['triage_issue', 'assign_issue', 'get_me', 'list_my_orgs', 'list_projects']);
    const { result } = renderHook(() => useFeatureAvailability('issueActions'), { wrapper });
    await waitFor(() => {
      expect(result.current.available).toBe(true);
    });
    expect(result.current.missingTools).toEqual([]);
  });

  it('returns the subset of required tools that are absent', async () => {
    // Snapshot has triage but lacks assign — `issueActions` becomes unavailable, but only the
    // `assign_issue` shows up as missing (proves the diff, not just "absent"). The initial
    // render's `data` is still undefined so we wait for the precise missing-tools list rather
    // than just `available: false` — both phases (loading + loaded) have `available: false`.
    mockSnapshot(['triage_issue']);
    const { result } = renderHook(() => useFeatureAvailability('issueActions'), { wrapper });
    await waitFor(() => {
      expect(result.current.missingTools).toEqual(['assign_issue']);
    });
    expect(result.current.available).toBe(false);
  });

  it('resolves workflow features via the `workflows` namespace', async () => {
    // `arguslog_triage_loop` requires list_issues + get_issue + triage_issue + assign_issue per
    // FEATURE_REQUIREMENTS.workflows. If the snapshot only has 3 of the 4, missingTools
    // contains exactly the absent one.
    mockSnapshot(['list_issues', 'get_issue', 'triage_issue']);
    const { result } = renderHook(() => useFeatureAvailability('arguslog_triage_loop'), {
      wrapper,
    });
    await waitFor(() => {
      expect(result.current.missingTools).toEqual(['assign_issue']);
    });
    expect(result.current.available).toBe(false);
  });

  it('unknown features (typo, future name) return available=true with no missing tools', async () => {
    // Defensive: if a caller passes a feature key that doesn't exist in FEATURE_REQUIREMENTS,
    // the lookup yields an empty required list, so the predicate is trivially satisfied.
    // Better than throwing — gating a typoed key shouldn't crash the screen.
    mockSnapshot(['anything']);
    const { result } = renderHook(() => useFeatureAvailability('does_not_exist'), { wrapper });
    await waitFor(() => {
      expect(result.current.available).toBe(true);
    });
    expect(result.current.missingTools).toEqual([]);
  });
});
