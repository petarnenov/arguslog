/**
 * Per-operator log of recent MCP tool runs. Each entry shows the tool name, an outcome
 * badge, a relative-time stamp, and exposes a Rerun button that pre-fills the Tools
 * screen with the same args. Mutating tools still go through ToolsScreen's existing
 * ConfirmDialog gate on actual submit — Rerun doesn't bypass safety.
 *
 * Rerun gating: a history entry's Rerun button is itself capability-gated. If the
 * connected server no longer advertises the tool (PAT scope shrunk, server downgraded),
 * the button is disabled with the standard missing-tools tooltip.
 */
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';

import { MUTATING_TOOLS } from '@arguslog/mcp-server/contract';

import {
  clearExecutionHistoryDomain,
  listExecutionHistory,
  type ToolExecution,
} from '../../../shared/domain/history';
import { getConnectionStatus } from '../../../shared/domain/connection';
import {
  Badge,
  Button,
  Card,
  EmptyState,
  Page,
} from '../../../shared/ui/components/primitives';

function relativeTime(iso: string, now: number = Date.now()): string {
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return 'unknown';
  const deltaSec = Math.round((now - then) / 1000);
  if (deltaSec < 5) return 'just now';
  if (deltaSec < 60) return `${deltaSec}s ago`;
  const deltaMin = Math.round(deltaSec / 60);
  if (deltaMin < 60) return `${deltaMin}m ago`;
  const deltaHr = Math.round(deltaMin / 60);
  if (deltaHr < 24) return `${deltaHr}h ago`;
  const deltaDay = Math.round(deltaHr / 24);
  return `${deltaDay}d ago`;
}

interface EntryProps {
  entry: ToolExecution;
  /** Set of tool names the connected server currently advertises. Empty during loading. */
  advertisedTools: Set<string>;
  onRerun: (entry: ToolExecution) => void;
}

function HistoryEntry({ entry, advertisedTools, onRerun }: EntryProps) {
  const toolAvailable = advertisedTools.size === 0 || advertisedTools.has(entry.toolName);
  const isMutating = MUTATING_TOOLS.includes(entry.toolName as (typeof MUTATING_TOOLS)[number]);

  return (
    <div className="rounded-xl border border-slate-800 bg-slate-950/40 p-3">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="flex items-center gap-2">
            <p className="font-mono text-sm font-medium text-white">{entry.toolName}</p>
            <Badge tone={entry.outcome === 'ok' ? 'success' : 'danger'}>{entry.outcome}</Badge>
            {isMutating ? <Badge tone="danger">write</Badge> : null}
          </div>
          <p className="mt-1 text-xs text-slate-500">
            {relativeTime(entry.ts)} · {entry.durationMs} ms
          </p>
        </div>
        <Button
          variant="secondary"
          disabled={!toolAvailable}
          title={
            toolAvailable
              ? undefined
              : `Tool "${entry.toolName}" is not advertised by the connected server.`
          }
          onClick={() => onRerun(entry)}
        >
          Rerun
        </Button>
      </div>

      <details className="mt-3">
        <summary className="cursor-pointer text-xs text-slate-400 hover:text-slate-200">
          Arguments + result
        </summary>
        <div className="mt-2 space-y-2 text-xs">
          <div>
            <p className="text-slate-500">args</p>
            <pre className="mt-1 overflow-x-auto rounded bg-slate-900 p-2 text-slate-200">
              {JSON.stringify(entry.args, null, 2)}
            </pre>
          </div>
          {entry.outcome === 'ok' ? (
            <div>
              <p className="text-slate-500">
                result {entry.truncated ? <span className="text-amber-400">(truncated)</span> : ''}
              </p>
              <pre className="mt-1 overflow-x-auto rounded bg-slate-900 p-2 text-slate-200">
                {entry.resultSummary ?? '(no result body)'}
              </pre>
            </div>
          ) : (
            <div>
              <p className="text-slate-500">error</p>
              <p className="mt-1 rounded bg-rose-900/30 p-2 text-rose-200">
                {entry.errorBucket ?? 'UNKNOWN'}: {entry.errorMessage ?? 'No message'}
              </p>
            </div>
          )}
        </div>
      </details>
    </div>
  );
}

export function HistoryScreen() {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const historyQuery = useQuery({
    queryKey: ['execution-history'],
    queryFn: listExecutionHistory,
  });
  const statusQuery = useQuery({
    queryKey: ['connection-status'],
    queryFn: getConnectionStatus,
  });
  const advertisedTools = new Set(statusQuery.data?.capabilitySnapshot?.toolNames ?? []);

  function rerun(entry: ToolExecution) {
    // Pre-fill the ToolsScreen form via the shared React Query cache key, then navigate.
    // ToolsScreen reads + consumes the cache on mount.
    queryClient.setQueryData(['tools-prefill'], { toolName: entry.toolName, args: entry.args });
    navigate('/tools');
  }

  async function clear() {
    await clearExecutionHistoryDomain();
    await queryClient.invalidateQueries({ queryKey: ['execution-history'] });
  }

  const entries = historyQuery.data ?? [];

  return (
    <Page
      title="Execution history"
      subtitle="Recent MCP tool runs. Rerun pre-fills the Tools screen — mutators still go through the confirm dialog."
      actions={
        entries.length > 0 ? (
          <Button variant="ghost" onClick={clear}>
            Clear history
          </Button>
        ) : undefined
      }
    >
      {entries.length === 0 ? (
        <EmptyState
          title="No tool executions yet"
          description="Run a tool from the Tools screen or any of the workflows — its args + result will land here."
        />
      ) : (
        <div className="space-y-3">
          {entries.map((entry) => (
            <HistoryEntry
              key={entry.id}
              entry={entry}
              advertisedTools={advertisedTools}
              onRerun={rerun}
            />
          ))}
        </div>
      )}
    </Page>
  );
}
