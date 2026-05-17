/**
 * Regression: `list_issues` and `list_issue_events` return cursor-paginated envelopes
 * (`{data, page}`) from the api, not bare arrays. Before this fix the domain functions
 * parsed with `z.array(IssueSummarySchema)` and the Issues screen reported the zod
 * error verbatim:
 *
 *   [{ "code": "invalid_type", "expected": "array", "received": "object", "path": [],
 *      "message": "Expected array, received object" }]
 *
 * These tests pin the unwrap so a future "let's wrap everything in an envelope" api
 * change doesn't silently re-break the issues panel.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { callRawTool } from '../../src/shared/domain/catalog';
import { listIssueEvents, listIssues } from '../../src/shared/domain/issues';

vi.mock('../../src/shared/domain/catalog', () => ({
  callRawTool: vi.fn(),
}));

describe('issues domain — pagination unwrap', () => {
  beforeEach(() => {
    vi.mocked(callRawTool).mockReset();
  });

  it('listIssues extracts the data[] from a {data, page} envelope', async () => {
    vi.mocked(callRawTool).mockResolvedValue({
      data: [
        { id: 1, title: 'Boom', status: 'unresolved', level: 'error' },
        { id: 2, title: 'Bang', status: 'resolved', level: 'warning' },
      ],
      page: { next: 'opaque-cursor-xyz' },
    });

    const rows = await listIssues({ projectId: 7 });

    // Consumer (IssuesScreen) does `issuesQuery.data?.map(...)` — must be a bare array.
    expect(Array.isArray(rows)).toBe(true);
    expect(rows).toHaveLength(2);
    expect(rows[0]?.title).toBe('Boom');
  });

  it('listIssues handles an empty page', async () => {
    vi.mocked(callRawTool).mockResolvedValue({ data: [], page: {} });

    const rows = await listIssues({ projectId: 7 });
    expect(rows).toEqual([]);
  });

  it('listIssueEvents extracts the data[] from the envelope', async () => {
    vi.mocked(callRawTool).mockResolvedValue({
      data: [
        {
          id: 42,
          issueId: 1,
          occurredAt: '2026-05-17T13:00:00Z',
          title: 'TypeError: x is undefined',
          level: 'error',
        },
      ],
      page: {},
    });

    const events = await listIssueEvents(7, 1, 5);
    expect(events).toHaveLength(1);
    expect(events[0]?.id).toBe(42);
  });

  it('listIssues rejects a bare array (regression: pre-fix tolerance would resurrect the bug)', async () => {
    // The api stopped returning bare arrays on these endpoints — if we ever silently
    // accept one again we'd lose pagination metadata without anyone noticing. Pin the
    // strict envelope shape so contract drift fails loudly.
    vi.mocked(callRawTool).mockResolvedValue([
      { id: 1, title: 'Boom', status: 'unresolved', level: 'error' },
    ]);

    await expect(listIssues({ projectId: 7 })).rejects.toThrow();
  });
});
