import { describe, expect, it, vi } from 'vitest';

vi.mock('../../src/shared/domain/issues', () => ({
  getIssue: vi.fn(async () => ({
    id: 101,
    title: 'Cannot render dashboard',
    status: 'unresolved',
    count: 12,
    latestEvent: {
      stacktrace: {
        frames: [
          {
            filename: 'src/Dashboard.tsx',
            line: 42,
            function: 'renderDashboard',
          },
        ],
      },
    },
  })),
  listIssueEvents: vi.fn(async () => [
    {
      id: 1,
      message: 'TypeError: cannot read property',
    },
  ]),
  listIssues: vi.fn(async (args: Record<string, unknown>) => {
    if ('seenInReleaseId' in args) {
      return [{ id: 2, title: 'Spike issue', level: 'fatal', status: 'unresolved' }];
    }
    return [
      { id: 1, title: 'New issue', level: 'error', status: 'unresolved' },
      { id: 2, title: 'Spike issue', level: 'fatal', status: 'unresolved' },
    ];
  }),
}));

vi.mock('../../src/shared/domain/releases', () => ({
  listReleases: vi.fn(async () => [
    { id: 10, version: '1.0.0' },
    { id: 11, version: '1.1.0' },
  ]),
}));

import {
  runInvestigateIssueWorkflow,
  runRegressionCheckWorkflow,
} from '../../src/shared/domain/workflows';

describe('workflow runners', () => {
  it('builds an investigate issue markdown report', async () => {
    const result = await runInvestigateIssueWorkflow(7, 101);

    expect(result.markdown).toContain('Investigate issue #101');
    expect(result.markdown).toContain('Cannot render dashboard');
    expect(result.steps).toHaveLength(2);
  });

  it('classifies release findings for a regression check', async () => {
    const result = await runRegressionCheckWorkflow(7, '1.1.0', '1.0.0');

    expect(result.markdown).toContain('Regression check');
    expect(result.markdown).toContain('NEW');
  });
});
