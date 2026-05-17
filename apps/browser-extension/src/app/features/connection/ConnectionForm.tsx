import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo, useState } from 'react';

import { connect, getConnectionStatus } from '../../../shared/domain/connection';
import { useI18n } from '../../../shared/hooks/useI18n';
import {
  getWorkspaceSelection,
  listMyOrgs,
  listProjects,
  updateWorkspaceSelection,
} from '../../../shared/domain/workspace';
import {
  Badge,
  Button,
  Card,
  Field,
  InlineError,
  Input,
  Label,
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
  const { t } = useI18n();
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

  const patPresent = data?.authSession.patPresent === true;

  const mutation = useMutation({
    mutationFn: connect,
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['connection-status'] });
      // Auto-pick the workspace when the operator has a single org/project pair.
      // Best-effort: any failure here (network, schema drift, MCP timeout) is swallowed
      // because the user can always finish the selection manually from /workspace, and
      // we don't want the foreground PAT-entry flow to look broken on an auto-pick
      // hiccup that has nothing to do with the connection itself.
      try {
        await tryAutoPickSoleWorkspace();
        await queryClient.invalidateQueries({ queryKey: ['connection-status'] });
      } catch {
        // intentional no-op
      }
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
        <div className="space-y-1">
          <div className="flex items-center gap-2">
            <Label>Personal access token</Label>
            {patPresent ? <Badge tone="success">PAT stored</Badge> : null}
          </div>
          <Input
            value={pat}
            onChange={(event) => setPat(event.target.value)}
            type="password"
            // Show a masked placeholder when storage already has a PAT so the operator
            // can tell at a glance their token persisted across reloads. The real value
            // is never echoed back (getConnectionStatus reports only `patPresent`),
            // which is why the input itself stays empty until they paste a rotation.
            placeholder={patPresent ? 'arglog_pat_••••••••••' : 'arglog_pat_...'}
          />
          <p className="text-xs text-slate-400">
            {patPresent
              ? 'A PAT is already stored. Paste a new one to rotate; leave blank to keep the current.'
              : 'The PAT is encrypted at rest and only decrypted in the background worker.'}
          </p>
        </div>

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
            {mutation.isPending ? t('btnConnecting') : t('btnConnect')}
          </Button>
        </div>
      </form>
    </Card>
  );
}

/**
 * If the freshly-authenticated PAT can see exactly one org with exactly one project,
 * write that pair into workspace-store so the operator skips the manual `/workspace`
 * selection step on first run. Multi-org or multi-project accounts skip the auto-pick
 * — selection there is a real choice, not boilerplate.
 *
 * Returns silently on every non-happy path (zero orgs, multiple, MCP transport error,
 * schema drift). The caller in {@link mutation.onSuccess} treats this as best-effort
 * — if it doesn't fire, the existing /workspace flow still works.
 */
async function tryAutoPickSoleWorkspace(): Promise<void> {
  const orgs = await listMyOrgs();
  const [org, ...rest] = orgs;
  // Need exactly one org; tsc's noUncheckedIndexedAccess insists on the destructure
  // guard before we touch org.id below.
  if (!org || rest.length > 0) return;
  const projects = await listProjects(org.id);
  const [project, ...projectRest] = projects;
  if (!project || projectRest.length > 0) return;

  // Preserve any prior `recents` from a previous session — workspace-store survives
  // across PAT rotations within the same chrome profile, and clobbering recents would
  // break the workspace switcher's "recently visited" affordance.
  const current = await getWorkspaceSelection();
  await updateWorkspaceSelection({
    ...current,
    orgId: org.id,
    orgSlug: org.slug,
    projectId: project.id,
  });
}
