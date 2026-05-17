import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ConnectionForm } from '../../src/app/features/connection/ConnectionForm';
import { connect, getConnectionStatus } from '../../src/shared/domain/connection';

vi.mock('../../src/shared/domain/connection', () => ({
  connect: vi.fn(),
  getConnectionStatus: vi.fn(),
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
      expect(screen.getByPlaceholderText('https://mcp.arguslog.org/mcp')).toHaveValue('https://staging.arguslog.org/mcp'),
    );
    expect(screen.getByRole('combobox')).toHaveValue('session');
    expect(screen.getByRole('checkbox', { name: 'Enable debug diagnostics' })).toBeChecked();
  });
});
