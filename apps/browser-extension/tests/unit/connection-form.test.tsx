import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ConnectionForm } from '../../src/app/features/connection/ConnectionForm';
import { connect, getConnectionStatus } from '../../src/shared/domain/connection';
import {
  getWorkspaceSelection,
  listMyOrgs,
  listProjects,
  updateWorkspaceSelection,
} from '../../src/shared/domain/workspace';

vi.mock('../../src/shared/domain/connection', () => ({
  connect: vi.fn(),
  getConnectionStatus: vi.fn(),
}));

vi.mock('../../src/shared/domain/workspace', () => ({
  getWorkspaceSelection: vi.fn(),
  listMyOrgs: vi.fn(),
  listProjects: vi.fn(),
  updateWorkspaceSelection: vi.fn(),
}));

function renderForm() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: {
        retry: false,
      },
    },
  });

  return render(
    <QueryClientProvider client={queryClient}>
      <ConnectionForm />
    </QueryClientProvider>,
  );
}

describe('ConnectionForm', () => {
  beforeEach(() => {
    vi.mocked(getConnectionStatus).mockResolvedValue({
      settings: {
        endpoint: 'https://mcp.arguslog.org/mcp',
        persistenceMode: 'persistent',
        debug: false,
        theme: 'system',
      },
      authSession: {
        patPresent: false,
        persistenceMode: 'persistent',
      },
      workspaceSelection: {
        recents: [],
      },
    });
    vi.mocked(connect).mockReset();
    vi.mocked(listMyOrgs).mockReset();
    vi.mocked(listProjects).mockReset();
    vi.mocked(updateWorkspaceSelection).mockReset();
    vi.mocked(getWorkspaceSelection).mockResolvedValue({ recents: [] });
  });

  it('shows plain-object connection errors and trims the submitted PAT', async () => {
    vi.mocked(connect).mockRejectedValue({
      bucket: 'INVALID_PAT',
      message: 'Invalid PAT.',
      status: 401,
    });

    renderForm();

    const user = userEvent.setup();
    await user.type(await screen.findByPlaceholderText('arglog_pat_...'), '  arglog_pat_test  ');
    await user.click(screen.getByRole('button', { name: 'Connect' }));

    await waitFor(() => expect(connect).toHaveBeenCalled());
    expect(vi.mocked(connect).mock.calls[0]?.[0]).toEqual({
      pat: 'arglog_pat_test',
      endpoint: 'https://mcp.arguslog.org/mcp',
      persistenceMode: 'persistent',
      debug: false,
    });
    expect(await screen.findByText('Invalid PAT.')).toBeInTheDocument();
  });

  it('hydrates untouched fields from loaded settings', async () => {
    vi.mocked(getConnectionStatus).mockResolvedValue({
      settings: {
        endpoint: 'https://staging.arguslog.org/mcp',
        persistenceMode: 'session',
        debug: true,
        theme: 'system',
      },
      authSession: {
        patPresent: false,
        persistenceMode: 'session',
      },
      workspaceSelection: {
        recents: [],
      },
    });

    renderForm();

    await waitFor(() =>
      expect(screen.getByPlaceholderText('https://mcp.arguslog.org/mcp')).toHaveValue(
        'https://staging.arguslog.org/mcp',
      ),
    );
    expect(screen.getByRole('combobox')).toHaveValue('session');
    expect(screen.getByRole('checkbox', { name: 'Enable debug diagnostics' })).toBeChecked();
  });

  it('shows the "PAT stored" badge and masked placeholder when a PAT is already persisted', async () => {
    vi.mocked(getConnectionStatus).mockResolvedValue({
      settings: {
        endpoint: 'https://mcp.arguslog.org/mcp',
        persistenceMode: 'persistent',
        debug: false,
        theme: 'system',
      },
      authSession: {
        patPresent: true,
        persistenceMode: 'persistent',
      },
      workspaceSelection: { recents: [] },
    });

    renderForm();

    // The badge is the operator's only visual cue that a PAT survived the reload.
    // Without it the input would look blank-and-not-saved (the bug we're fixing).
    expect(await screen.findByText('PAT stored')).toBeInTheDocument();
    expect(screen.getByPlaceholderText('arglog_pat_••••••••••')).toBeInTheDocument();
    expect(screen.queryByPlaceholderText('arglog_pat_...')).not.toBeInTheDocument();
  });

  it('does NOT show the badge when no PAT is stored yet (onboarding state)', async () => {
    renderForm();

    // Wait for the form to render before asserting the negative — otherwise we'd
    // be racing the query and could falsely pass while data is still loading.
    await screen.findByPlaceholderText('arglog_pat_...');
    expect(screen.queryByText('PAT stored')).not.toBeInTheDocument();
  });

  it('auto-picks the workspace when the connected PAT sees exactly one org with one project', async () => {
    vi.mocked(connect).mockResolvedValue({
      // Shape is irrelevant — the form ignores the connect() return value and only acts on success.
    } as never);
    vi.mocked(listMyOrgs).mockResolvedValue([{ id: 42, slug: 'acme', name: 'Acme' }] as never);
    vi.mocked(listProjects).mockResolvedValue([
      { id: 7, slug: 'web', name: 'Web', platform: 'react' },
    ] as never);
    vi.mocked(updateWorkspaceSelection).mockResolvedValue({ recents: [] } as never);

    renderForm();

    const user = userEvent.setup();
    await user.type(await screen.findByPlaceholderText('arglog_pat_...'), 'arglog_pat_test');
    await user.click(screen.getByRole('button', { name: 'Connect' }));

    await waitFor(() => expect(updateWorkspaceSelection).toHaveBeenCalledTimes(1));
    // Auto-pick must preserve any existing `recents` from a prior session AND write the
    // canonical (orgId, orgSlug, projectId) triple so /workspace + /issues pick it up.
    expect(vi.mocked(updateWorkspaceSelection).mock.calls[0]?.[0]).toEqual({
      recents: [],
      orgId: 42,
      orgSlug: 'acme',
      projectId: 7,
    });
  });

  it('does NOT auto-pick when the connected PAT sees multiple orgs', async () => {
    vi.mocked(connect).mockResolvedValue({} as never);
    vi.mocked(listMyOrgs).mockResolvedValue([
      { id: 42, slug: 'acme', name: 'Acme' },
      { id: 43, slug: 'globex', name: 'Globex' },
    ] as never);

    renderForm();

    const user = userEvent.setup();
    await user.type(await screen.findByPlaceholderText('arglog_pat_...'), 'arglog_pat_test');
    await user.click(screen.getByRole('button', { name: 'Connect' }));

    await waitFor(() => expect(connect).toHaveBeenCalled());
    // Multi-org accounts must choose manually — auto-pick would lock them into the
    // first org returned by the API, which is alphabetical and meaningless to the operator.
    expect(updateWorkspaceSelection).not.toHaveBeenCalled();
    // Crucially we also did NOT call listProjects on a multi-org account — skipping
    // the probe early saves a round-trip and avoids picking a project from a wrong org.
    expect(listProjects).not.toHaveBeenCalled();
  });

  it('does NOT auto-pick when the single org has multiple projects', async () => {
    vi.mocked(connect).mockResolvedValue({} as never);
    vi.mocked(listMyOrgs).mockResolvedValue([{ id: 42, slug: 'acme', name: 'Acme' }] as never);
    vi.mocked(listProjects).mockResolvedValue([
      { id: 7, slug: 'web', name: 'Web', platform: 'react' },
      { id: 8, slug: 'api', name: 'API', platform: 'java-spring' },
    ] as never);

    renderForm();

    const user = userEvent.setup();
    await user.type(await screen.findByPlaceholderText('arglog_pat_...'), 'arglog_pat_test');
    await user.click(screen.getByRole('button', { name: 'Connect' }));

    await waitFor(() => expect(listProjects).toHaveBeenCalled());
    expect(updateWorkspaceSelection).not.toHaveBeenCalled();
  });

  it('swallows auto-pick failures without surfacing them on the connect form', async () => {
    vi.mocked(connect).mockResolvedValue({} as never);
    vi.mocked(listMyOrgs).mockRejectedValue(new Error('mcp transport blew up'));

    renderForm();

    const user = userEvent.setup();
    await user.type(await screen.findByPlaceholderText('arglog_pat_...'), 'arglog_pat_test');
    await user.click(screen.getByRole('button', { name: 'Connect' }));

    await waitFor(() => expect(connect).toHaveBeenCalled());
    // Auto-pick is best-effort. If the org-list probe fails we don't want to flash a
    // red error banner — the operator can still navigate to /workspace and pick by hand.
    expect(screen.queryByText('mcp transport blew up')).not.toBeInTheDocument();
    expect(updateWorkspaceSelection).not.toHaveBeenCalled();
  });
});
