import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo, useState } from 'react';

import { connect, getConnectionStatus } from '../../../shared/domain/connection';
import {
  Button,
  Card,
  Field,
  InlineError,
  Input,
  Select,
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

export function ConnectionForm(props: { compact?: boolean; onConnected?: () => void }) {
  const queryClient = useQueryClient();
  const { data } = useQuery({
    queryKey: ['connection-status'],
    queryFn: getConnectionStatus,
  });
  const defaults = useMemo(
    () => ({
      endpoint: data?.settings.endpoint ?? 'https://mcp.arguslog.org/mcp',
      persistenceMode: data?.settings.persistenceMode ?? 'persistent',
      debug: data?.settings.debug ?? false,
    }),
    [data],
  );

  const [pat, setPat] = useState('');
  const [endpoint, setEndpoint] = useState(defaults.endpoint);
  const [endpointTouched, setEndpointTouched] = useState(false);
  const [persistenceMode, setPersistenceMode] = useState<'persistent' | 'session'>(
    defaults.persistenceMode,
  );
  const [persistenceTouched, setPersistenceTouched] = useState(false);
  const [debug, setDebug] = useState(defaults.debug);
  const [debugTouched, setDebugTouched] = useState(false);

  useEffect(() => {
    if (!endpointTouched) {
      setEndpoint(defaults.endpoint);
    }
  }, [defaults.endpoint, endpointTouched]);

  useEffect(() => {
    if (!persistenceTouched) {
      setPersistenceMode(defaults.persistenceMode);
    }
  }, [defaults.persistenceMode, persistenceTouched]);

  useEffect(() => {
    if (!debugTouched) {
      setDebug(defaults.debug);
    }
  }, [defaults.debug, debugTouched]);

  const mutation = useMutation({
    mutationFn: connect,
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['connection-status'] });
      props.onConnected?.();
      setPat('');
    },
  });

  return (
    <Card title="Connect to Arguslog MCP">
      <form
        className="space-y-3"
        onSubmit={(event) => {
          event.preventDefault();
          mutation.mutate({
            pat: pat.trim(),
            endpoint: endpoint.trim(),
            persistenceMode,
            debug,
          });
        }}
      >
        <Field
          label="Personal access token"
          description="The PAT is encrypted at rest and only decrypted in the background worker."
        >
          <Input
            value={pat}
            onChange={(event) => setPat(event.target.value)}
            type="password"
            placeholder="arglog_pat_..."
          />
        </Field>

        <div className={props.compact ? 'space-y-3' : 'grid gap-3 md:grid-cols-2'}>
          <Field label="Endpoint">
            <Input
              value={endpoint}
              onChange={(event) => {
                setEndpointTouched(true);
                setEndpoint(event.target.value);
              }}
              placeholder="https://mcp.arguslog.org/mcp"
            />
          </Field>
          <Field label="Persistence">
            <Select
              value={persistenceMode}
              onChange={(event) => {
                setPersistenceTouched(true);
                setPersistenceMode(event.target.value as 'persistent' | 'session');
              }}
            >
              <option value="persistent">Persistent (encrypted)</option>
              <option value="session">Session only</option>
            </Select>
          </Field>
        </div>

        <label className="flex items-center gap-2 text-sm text-slate-300">
          <input
            checked={debug}
            onChange={(event) => {
              setDebugTouched(true);
              setDebug(event.target.checked);
            }}
            type="checkbox"
          />
          Enable debug diagnostics
        </label>

        <InlineError message={getErrorMessage(mutation.error)} />

        <div className="flex items-center justify-between gap-3">
          <div className="text-xs text-slate-400">
            {data?.authSession.accountSummary
              ? `Current identity: ${getAccountLabel(data.authSession.accountSummary)}`
              : 'No connected identity yet.'}
          </div>
          <Button disabled={mutation.isPending || pat.trim().length === 0} type="submit">
            {mutation.isPending ? 'Connecting…' : 'Connect'}
          </Button>
        </div>
      </form>
    </Card>
  );
}
