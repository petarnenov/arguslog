import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';

import { WHITE_SCREEN_TEST_ERROR_MESSAGE } from '../../../shared/constants/diagnostics';
import { disconnect, getConnectionStatus, updateSettings } from '../../../shared/domain/connection';
import { Button, Card, Field, Page, Select } from '../../../shared/ui/components/primitives';
import { downloadFile } from '../../../shared/utils/export';
import { sendBackgroundRequest } from '../../../shared/utils/messaging';
import { DiagnosticBundleSchema } from '../../../shared/validation/models';
import { ConnectionForm } from '../connection/ConnectionForm';

export function SettingsScreen() {
  const [triggerWhiteScreen, setTriggerWhiteScreen] = useState(false);
  const queryClient = useQueryClient();
  const { data } = useQuery({
    queryKey: ['connection-status'],
    queryFn: getConnectionStatus,
  });

  const updateMutation = useMutation({
    mutationFn: updateSettings,
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['connection-status'] });
    },
  });

  const disconnectMutation = useMutation({
    mutationFn: disconnect,
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['connection-status'] });
    },
  });

  if (triggerWhiteScreen) {
    throw new Error(WHITE_SCREEN_TEST_ERROR_MESSAGE);
  }

  return (
    <Page title="Settings" subtitle="PAT storage, endpoint overrides, diagnostics export, and connection reset.">
      <ConnectionForm />

      <Card title="Display">
        <Field label="Theme">
          <Select
            value={data?.settings.theme ?? 'system'}
            onChange={(event) => updateMutation.mutate({ theme: event.target.value as 'system' | 'dark' | 'light' })}
          >
            <option value="system">System</option>
            <option value="dark">Dark</option>
            <option value="light">Light</option>
          </Select>
        </Field>
      </Card>

      <Card title="Diagnostics">
        <div className="flex flex-wrap gap-2">
          <Button
            variant="secondary"
            onClick={async () => {
              const bundle = await sendBackgroundRequest(
                { type: 'diagnostics/export' },
                DiagnosticBundleSchema,
              );
              downloadFile(
                `arguslog-diagnostics-${new Date().toISOString()}.json`,
                JSON.stringify(bundle, null, 2),
              );
            }}
          >
            Export diagnostics
          </Button>
          <Button variant="danger" onClick={() => setTriggerWhiteScreen(true)}>
            Trigger white-screen test
          </Button>
          <Button
            variant="danger"
            onClick={() => disconnectMutation.mutate()}
          >
            Disconnect
          </Button>
        </div>
      </Card>
    </Page>
  );
}
