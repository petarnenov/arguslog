import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import browser from 'webextension-polyfill';

import { disconnect, getConnectionStatus, openSidePanel } from '../../../shared/domain/connection';
import {
  Badge,
  Button,
  Card,
  EmptyState,
  InlineError,
} from '../../../shared/ui/components/primitives';
import { getAccountLabel } from '../../../shared/utils/account';

function getErrorMessage(error: unknown): string | undefined {
  if (error instanceof Error) {
    return error.message;
  }

  if (
    typeof error === 'object' &&
    error !== null &&
    'message' in error &&
    typeof error.message === 'string'
  ) {
    return error.message;
  }

  return undefined;
}

export function PopupApp() {
  const queryClient = useQueryClient();
  const statusQuery = useQuery({ queryKey: ['connection-status'], queryFn: getConnectionStatus });
  const openSidePanelMutation = useMutation({ mutationFn: openSidePanel });
  const disconnectMutation = useMutation({
    mutationFn: disconnect,
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['connection-status'] });
    },
  });

  if (!statusQuery.data?.authSession.patPresent) {
    return (
      <div className="w-96 p-4">
        <Card title="Arguslog MCP Console">
          <EmptyState
            title="Not connected"
            description="Open the options page or the side panel and connect with a PAT."
          />
        </Card>
      </div>
    );
  }

  return (
    <div className="w-96 space-y-4 p-4">
      <Card title="Connection status">
        <div className="space-y-3 text-sm text-slate-300">
          <p className="font-medium text-white">
            {getAccountLabel(statusQuery.data.authSession.accountSummary)}
          </p>
          <div className="flex flex-wrap gap-2">
            <Badge>{statusQuery.data.capabilitySnapshot?.toolNames.length ?? 0} tools</Badge>
            <Badge>{statusQuery.data.capabilitySnapshot?.promptIds.length ?? 0} prompts</Badge>
          </div>
          <p>Selected project: {statusQuery.data.workspaceSelection.projectId ?? 'n/a'}</p>
        </div>
      </Card>

      <div className="grid gap-2">
        <Button
          disabled={openSidePanelMutation.isPending}
          onClick={() => openSidePanelMutation.mutate()}
        >
          {openSidePanelMutation.isPending ? 'Opening…' : 'Open side panel'}
        </Button>
        <InlineError message={getErrorMessage(openSidePanelMutation.error)} />
        <Button variant="secondary" onClick={() => browser.runtime.openOptionsPage()}>
          Open options
        </Button>
        <Button variant="danger" onClick={() => disconnectMutation.mutate()}>
          Disconnect
        </Button>
      </div>
    </div>
  );
}
