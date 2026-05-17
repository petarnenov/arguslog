/**
 * Tests for `CreateProjectCard`. The card is the last guarded-write affordance in the
 * extension — its rendering is gated by `projects` feature availability + an org
 * selection, and submit goes through ConfirmDialog → createProject domain function →
 * workspace selection auto-flip.
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { CreateProjectCard } from '../../src/app/features/workspace/CreateProjectCard';
import { getConnectionStatus } from '../../src/shared/domain/connection';
import { createProject } from '../../src/shared/domain/projects';
import { updateWorkspaceSelection } from '../../src/shared/domain/workspace';
import type { ConnectionStatus } from '../../src/shared/validation/models';

vi.mock('../../src/shared/domain/connection', () => ({
  getConnectionStatus: vi.fn(),
}));
vi.mock('../../src/shared/domain/projects', () => ({
  createProject: vi.fn(),
}));
vi.mock('../../src/shared/domain/workspace', () => ({
  updateWorkspaceSelection: vi.fn(),
}));

function renderCard(props: { orgId?: number; orgSlug?: string }) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <CreateProjectCard orgId={props.orgId} orgSlug={props.orgSlug} />
    </QueryClientProvider>,
  );
}

function mockCapabilitySnapshot(toolNames: string[]) {
  vi.mocked(getConnectionStatus).mockResolvedValue({
    settings: {
      endpoint: 'https://mcp.arguslog.org/mcp',
      persistenceMode: 'session',
      debug: false,
      theme: 'system',
    },
    authSession: { patPresent: true, persistenceMode: 'session' },
    capabilitySnapshot: {
      serverVersion: '2.5.0',
      toolNames,
      promptIds: [],
      detectedScopes: [],
      fetchedAt: new Date().toISOString(),
    },
    workspaceSelection: { orgId: 1, orgSlug: 'acme', recents: [] },
  } as ConnectionStatus);
}

describe('CreateProjectCard', () => {
  beforeEach(() => {
    vi.mocked(createProject).mockReset();
    vi.mocked(updateWorkspaceSelection).mockReset();
  });

  it('renders nothing when `create_project` is absent from capabilities', async () => {
    mockCapabilitySnapshot(['get_me']); // no create_project
    const { container } = renderCard({ orgId: 1, orgSlug: 'acme' });
    // Wait a tick — the hook resolves async; if the card was going to render, it would by now.
    await new Promise((r) => setTimeout(r, 50));
    expect(container.querySelector('.space-y-3')).toBeNull();
    expect(screen.queryByText(/Create project/i)).toBeNull();
  });

  it('renders nothing when no org is selected (even if the feature is available)', async () => {
    mockCapabilitySnapshot(['create_project']);
    const { container } = renderCard({ orgId: undefined });
    await new Promise((r) => setTimeout(r, 50));
    expect(container.querySelector('.space-y-3')).toBeNull();
  });

  it('renders the form when feature available + org selected', async () => {
    mockCapabilitySnapshot(['create_project']);
    renderCard({ orgId: 1, orgSlug: 'acme' });
    await waitFor(() => {
      expect(screen.getByPlaceholderText(/Project name/i)).toBeInTheDocument();
    });
    expect(screen.getByPlaceholderText(/Platform/i)).toBeInTheDocument();
  });

  it('disables Create button when name is < 2 chars or platform empty', async () => {
    mockCapabilitySnapshot(['create_project']);
    renderCard({ orgId: 1, orgSlug: 'acme' });
    await waitFor(() => screen.getByPlaceholderText(/Project name/i));

    const createBtn = screen.getByRole('button', { name: /Create project/i });
    expect(createBtn).toBeDisabled();

    const user = userEvent.setup();
    await user.type(screen.getByPlaceholderText(/Project name/i), 'a'); // 1 char — still invalid
    expect(createBtn).toBeDisabled();

    await user.type(screen.getByPlaceholderText(/Project name/i), 'cme'); // 'acme' — 4 chars
    expect(createBtn).toBeDisabled(); // platform still missing

    await user.type(screen.getByPlaceholderText(/Platform/i), 'javascript');
    expect(createBtn).not.toBeDisabled();
  });

  it('submits via ConfirmDialog and flips workspace to the new project', async () => {
    mockCapabilitySnapshot(['create_project']);
    vi.mocked(createProject).mockResolvedValue({
      project: { id: 42, name: 'acme-web', slug: 'acme-web', orgId: 1, platform: 'javascript' },
    } as Awaited<ReturnType<typeof createProject>>);

    renderCard({ orgId: 1, orgSlug: 'acme' });
    await waitFor(() => screen.getByPlaceholderText(/Project name/i));

    const user = userEvent.setup();
    await user.type(screen.getByPlaceholderText(/Project name/i), 'acme-web');
    await user.type(screen.getByPlaceholderText(/Platform/i), 'javascript');
    await user.click(screen.getByRole('button', { name: /Create project/i }));

    // ConfirmDialog opens with the name + platform + org in the description.
    expect(screen.getByText(/Create project "acme-web"/)).toBeInTheDocument();
    expect(screen.getByText(/javascript/)).toBeInTheDocument();
    expect(screen.getByText(/under acme/)).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /^Create$/ }));

    await waitFor(() => {
      expect(createProject).toHaveBeenCalledWith({
        orgId: 1,
        body: { name: 'acme-web', platform: 'javascript' },
      });
    });

    // Workspace selection should land on the new project id.
    await waitFor(() => {
      expect(updateWorkspaceSelection).toHaveBeenCalled();
    });
    const lastCall = vi.mocked(updateWorkspaceSelection).mock.calls.at(-1)?.[0];
    expect(lastCall?.projectId).toBe(42);
  });
});
