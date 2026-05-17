import { useQuery } from '@tanstack/react-query';
import { MemoryRouter, NavLink, Navigate, Route, Routes } from 'react-router-dom';

import { getConnectionStatus } from '../../shared/domain/connection';
import { Badge } from '../../shared/ui/components/primitives';
import { getAccountLabel } from '../../shared/utils/account';
import { ConnectScreen } from '../features/connection/ConnectScreen';
import { HistoryScreen } from '../features/history/HistoryScreen';
import { IssuesScreen } from '../features/issues/IssuesScreen';
import { PlaybooksScreen } from '../features/playbooks/PlaybooksScreen';
import { ReleasesScreen } from '../features/releases/ReleasesScreen';
import { SettingsScreen } from '../features/settings/SettingsScreen';
import { ToolsScreen } from '../features/tools/ToolsScreen';
import { WorkflowsScreen } from '../features/workflows/WorkflowsScreen';
import { WorkspaceScreen } from '../features/workspace/WorkspaceScreen';

// `/connect` is deliberately absent: it duplicates the PAT-entry form already
// owned by `/settings`, and unauthenticated operators reach it automatically
// through the `startPath` redirect below. Once a PAT exists, Settings is the
// single management surface — adding `/connect` to the sidebar again would
// resurrect the duplication report the operator filed against the sidepanel.
const navItems = [
  ['/workspace', 'Workspace'],
  ['/issues', 'Issues'],
  ['/releases', 'Releases'],
  ['/workflows', 'Workflows'],
  ['/tools', 'Tools'],
  ['/history', 'History'],
  ['/playbooks', 'Playbooks'],
  ['/settings', 'Settings'],
] as const;

export function SidepanelApp() {
  const statusQuery = useQuery({
    queryKey: ['connection-status'],
    queryFn: getConnectionStatus,
  });

  const startPath = statusQuery.data?.authSession.patPresent ? '/workspace' : '/connect';

  return (
    <MemoryRouter initialEntries={[startPath]}>
      <div className="flex min-h-screen bg-slate-950/20 text-slate-100">
        <aside className="w-40 border-r border-slate-800 bg-slate-950/50 p-3">
          <div className="mb-4">
            <p className="text-sm font-semibold text-white">Arguslog MCP</p>
            <p className="mt-1 text-xs text-slate-400">
              {getAccountLabel(statusQuery.data?.authSession.accountSummary)}
            </p>
            {statusQuery.data?.capabilitySnapshot ? (
              <div className="mt-2">
                <Badge>{statusQuery.data.capabilitySnapshot.toolNames.length} tools</Badge>
              </div>
            ) : null}
          </div>

          <nav className="space-y-1">
            {navItems.map(([to, label]) => (
              <NavLink
                key={to}
                to={to}
                className={({ isActive }) =>
                  `block rounded-xl px-3 py-2 text-sm ${isActive ? 'bg-blue-500/20 text-white' : 'text-slate-300 hover:bg-slate-900'}`
                }
              >
                {label}
              </NavLink>
            ))}
          </nav>
        </aside>

        <main className="min-w-0 flex-1 p-4">
          <Routes>
            <Route path="/" element={<Navigate replace to={startPath} />} />
            <Route path="/connect" element={<ConnectScreen />} />
            <Route path="/workspace" element={<WorkspaceScreen />} />
            <Route path="/issues" element={<IssuesScreen />} />
            <Route path="/releases" element={<ReleasesScreen />} />
            <Route path="/workflows" element={<WorkflowsScreen />} />
            <Route path="/tools" element={<ToolsScreen />} />
            <Route path="/history" element={<HistoryScreen />} />
            <Route path="/playbooks" element={<PlaybooksScreen />} />
            <Route path="/settings" element={<SettingsScreen />} />
          </Routes>
        </main>
      </div>
    </MemoryRouter>
  );
}
