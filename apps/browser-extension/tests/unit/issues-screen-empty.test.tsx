import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { IssuesScreen } from '../../src/app/features/issues/IssuesScreen';
import { getConnectionStatus } from '../../src/shared/domain/connection';
import { listIssues } from '../../src/shared/domain/issues';

vi.mock('../../src/shared/domain/connection', () => ({
  getConnectionStatus: vi.fn(),
}));

vi.mock('../../src/shared/domain/issues', () => ({
  listIssues: vi.fn(),
  getIssue: vi.fn(),
  listIssueEvents: vi.fn(),
  triageIssue: vi.fn(),
  assignIssue: vi.fn(),
}));

vi.mock('../../src/shared/domain/workspace', () => ({
  listMembers: vi.fn().mockResolvedValue([]),
}));

vi.mock('../../src/shared/hooks/useFeatureAvailability', () => ({
  useFeatureAvailability: () => ({ available: true, missingTools: [] }),
}));

function renderAt(initialPath = '/issues') {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });

  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initialPath]}>
        <Routes>
          <Route path="/issues" element={<IssuesScreen />} />
          <Route path="/workspace" element={<div data-testid="workspace-stub" />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('IssuesScreen — empty state CTA', () => {
  beforeEach(() => {
    vi.mocked(listIssues).mockReset();
  });

  it('renders a "Pick a project" CTA that navigates to /workspace when no project is selected', async () => {
    // Connection is healthy but the operator never picked a project. Pre-fix this rendered
    // the bare EmptyState with no path forward; the CTA closes that dead end.
    vi.mocked(getConnectionStatus).mockResolvedValue({
      settings: {
        endpoint: 'https://mcp.arguslog.org/mcp',
        persistenceMode: 'persistent',
        debug: false,
        theme: 'system',
      },
      authSession: { patPresent: true, persistenceMode: 'persistent' },
      workspaceSelection: { recents: [] }, // no orgId/projectId → empty path
    });

    renderAt();

    const cta = await screen.findByTestId('issues-pick-project-cta');
    expect(cta).toHaveTextContent('Pick a project');

    const user = userEvent.setup();
    await user.click(cta);

    await waitFor(() => expect(screen.getByTestId('workspace-stub')).toBeInTheDocument());
  });

  it('surfaces an inline error banner when the issues query rejects', async () => {
    vi.mocked(getConnectionStatus).mockResolvedValue({
      settings: {
        endpoint: 'https://mcp.arguslog.org/mcp',
        persistenceMode: 'persistent',
        debug: false,
        theme: 'system',
      },
      authSession: { patPresent: true, persistenceMode: 'persistent' },
      workspaceSelection: { orgId: 1, orgSlug: 'acme', projectId: 7, recents: [] },
    });
    vi.mocked(listIssues).mockRejectedValue(new Error('401 — PAT revoked'));

    renderAt();

    // 401 / 403 used to render as an undifferentiated "No issues found" empty list. The
    // banner makes the auth failure explicit so the operator knows to rotate their PAT.
    const banner = await screen.findByTestId('issues-error-banner');
    expect(banner).toHaveTextContent("Couldn't load issues");
    expect(banner).toHaveTextContent('401 — PAT revoked');
  });

  it('does NOT show the error banner on the happy path', async () => {
    vi.mocked(getConnectionStatus).mockResolvedValue({
      settings: {
        endpoint: 'https://mcp.arguslog.org/mcp',
        persistenceMode: 'persistent',
        debug: false,
        theme: 'system',
      },
      authSession: { patPresent: true, persistenceMode: 'persistent' },
      workspaceSelection: { orgId: 1, orgSlug: 'acme', projectId: 7, recents: [] },
    });
    vi.mocked(listIssues).mockResolvedValue([]);

    renderAt();

    // Wait for the filters card to render (proves the screen loaded past the "no project" guard)
    // before asserting the negative.
    await screen.findByText('Filters');
    expect(screen.queryByTestId('issues-error-banner')).not.toBeInTheDocument();
  });
});
