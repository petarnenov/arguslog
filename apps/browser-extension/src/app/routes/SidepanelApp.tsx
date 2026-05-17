import { useQuery } from '@tanstack/react-query';
import { lazy, Suspense } from 'react';
import { MemoryRouter, NavLink, Navigate, Route, Routes } from 'react-router-dom';

import { getConnectionStatus } from '../../shared/domain/connection';
import { useI18n, type I18nKey } from '../../shared/hooks/useI18n';
import { Badge } from '../../shared/ui/components/primitives';
import { getAccountLabel } from '../../shared/utils/account';

// Each screen ships as its own chunk loaded on first navigation, not upfront. The Connect
// screen is the only one a fresh install lands on, so its chunk is the only one that
// shows up on the cold-start critical path; the other seven screens stream in as the
// operator navigates. Vitest tests assert sidebar nav contents without rendering each
// screen, so the lazy-shape doesn't break the unit suite.
//
// Named-export → default-export interop: React.lazy demands a default export, but our
// screens export named functions. The `.then(m => ({ default: m.X }))` adapter keeps the
// named exports as the source of truth in their own files (better for tree-shaking
// surface + matches the codebase convention).
const ConnectScreen = lazy(() =>
  import('../features/connection/ConnectScreen').then((m) => ({ default: m.ConnectScreen })),
);
const HistoryScreen = lazy(() =>
  import('../features/history/HistoryScreen').then((m) => ({ default: m.HistoryScreen })),
);
const IssuesScreen = lazy(() =>
  import('../features/issues/IssuesScreen').then((m) => ({ default: m.IssuesScreen })),
);
const PlaybooksScreen = lazy(() =>
  import('../features/playbooks/PlaybooksScreen').then((m) => ({ default: m.PlaybooksScreen })),
);
const ReleasesScreen = lazy(() =>
  import('../features/releases/ReleasesScreen').then((m) => ({ default: m.ReleasesScreen })),
);
const SettingsScreen = lazy(() =>
  import('../features/settings/SettingsScreen').then((m) => ({ default: m.SettingsScreen })),
);
const ToolsScreen = lazy(() =>
  import('../features/tools/ToolsScreen').then((m) => ({ default: m.ToolsScreen })),
);
const WorkflowsScreen = lazy(() =>
  import('../features/workflows/WorkflowsScreen').then((m) => ({ default: m.WorkflowsScreen })),
);
const WorkspaceScreen = lazy(() =>
  import('../features/workspace/WorkspaceScreen').then((m) => ({ default: m.WorkspaceScreen })),
);

// `/connect` is deliberately absent: it duplicates the PAT-entry form already
// owned by `/settings`, and unauthenticated operators reach it automatically
// through the `startPath` redirect below. Once a PAT exists, Settings is the
// single management surface — adding `/connect` to the sidebar again would
// resurrect the duplication report the operator filed against the sidepanel.
const navItems: ReadonlyArray<readonly [string, I18nKey]> = [
  ['/workspace', 'navWorkspace'],
  ['/issues', 'navIssues'],
  ['/releases', 'navReleases'],
  ['/workflows', 'navWorkflows'],
  ['/tools', 'navTools'],
  ['/history', 'navHistory'],
  ['/playbooks', 'navPlaybooks'],
  ['/settings', 'navSettings'],
];

/**
 * Minimal Suspense fallback shown for the ~50-200ms it takes to fetch a screen chunk on
 * first navigation. Kept dependency-free (plain Tailwind div) so it doesn't pull in any
 * heavier components into the initial bundle that lazy-loading was meant to avoid.
 */
function ScreenLoading() {
  return (
    <div className="flex h-full items-center justify-center p-8 text-sm text-slate-400">
      Loading…
    </div>
  );
}

export function SidepanelApp() {
  const { t } = useI18n();
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
            {navItems.map(([to, labelKey]) => (
              <NavLink
                key={to}
                to={to}
                className={({ isActive }) =>
                  `block rounded-xl px-3 py-2 text-sm ${isActive ? 'bg-blue-500/20 text-white' : 'text-slate-300 hover:bg-slate-900'}`
                }
              >
                {t(labelKey)}
              </NavLink>
            ))}
          </nav>
        </aside>

        <main className="min-w-0 flex-1 p-4">
          <Suspense fallback={<ScreenLoading />}>
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
          </Suspense>
        </main>
      </div>
    </MemoryRouter>
  );
}
