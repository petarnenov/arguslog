import { createBrowserRouter, Navigate } from 'react-router';

import { RequireAuth } from './auth/RequireAuth';
import { AppShellLayout } from './layouts/AppShellLayout';
import { AdminPage } from './pages/AdminPage';
import { AlertDestinationsPage } from './pages/AlertDestinationsPage';
import { AlertRulesPage } from './pages/AlertRulesPage';
import { AuthCallbackPage } from './pages/AuthCallbackPage';
import { ConnectProjectPage } from './pages/ConnectProjectPage';
import { IssueDetailPage } from './pages/IssueDetailPage';
import { IssuesPage } from './pages/IssuesPage';
import { NotFoundPage } from './pages/NotFoundPage';
import { OnboardingPage } from './pages/OnboardingPage';
import { OrgMembersPage } from './pages/OrgMembersPage';
import { OrgsLandingPage } from './pages/OrgsLandingPage';
import { PersonalAccessTokensPage } from './pages/PersonalAccessTokensPage';
import { ProjectKeysPage } from './pages/ProjectKeysPage';
import { ProjectsPage } from './pages/ProjectsPage';
import { ReleaseDetailPage } from './pages/ReleaseDetailPage';
import { ReleasesPage } from './pages/ReleasesPage';
import { SlackIntegrationsPage } from './pages/SlackIntegrationsPage';

export const router = createBrowserRouter([
  { path: '/', element: <Navigate to="/orgs" replace /> },
  { path: '/auth/callback', element: <AuthCallbackPage /> },
  {
    element: (
      <RequireAuth>
        <AppShellLayout />
      </RequireAuth>
    ),
    children: [
      { path: '/onboarding', element: <OnboardingPage /> },
      { path: '/orgs', element: <OrgsLandingPage /> },
      { path: '/orgs/:orgSlug/projects', element: <ProjectsPage /> },
      { path: '/orgs/:orgSlug/projects/:projectId/issues', element: <IssuesPage /> },
      {
        path: '/orgs/:orgSlug/projects/:projectId/issues/:issueId',
        element: <IssueDetailPage />,
      },
      { path: '/orgs/:orgSlug/projects/:projectId/keys', element: <ProjectKeysPage /> },
      { path: '/orgs/:orgSlug/projects/:projectId/connect', element: <ConnectProjectPage /> },
      { path: '/orgs/:orgSlug/projects/:projectId/alert-rules', element: <AlertRulesPage /> },
      { path: '/orgs/:orgSlug/projects/:projectId/releases', element: <ReleasesPage /> },
      {
        path: '/orgs/:orgSlug/projects/:projectId/releases/:releaseId',
        element: <ReleaseDetailPage />,
      },
      { path: '/orgs/:orgSlug/members', element: <OrgMembersPage /> },
      { path: '/orgs/:orgSlug/destinations', element: <AlertDestinationsPage /> },
      { path: '/orgs/:orgSlug/integrations/slack', element: <SlackIntegrationsPage /> },
      // Back-compat redirects from the old /settings/* URLs. Bookmarks, copy-pasted links,
      // and Slack OAuth callbacks still encoded with the old path continue to work and land
      // on the canonical page. Drop after a release or two when stragglers have re-bookmarked.
      {
        path: '/orgs/:orgSlug/settings',
        element: <Navigate to="../members" relative="path" replace />,
      },
      {
        path: '/orgs/:orgSlug/settings/destinations',
        element: <Navigate to="../../destinations" relative="path" replace />,
      },
      {
        path: '/orgs/:orgSlug/settings/integrations/slack',
        element: <Navigate to="../../../integrations/slack" relative="path" replace />,
      },
      {
        path: '/orgs/:orgSlug/projects/:projectId/settings/keys',
        element: <Navigate to="../../keys" relative="path" replace />,
      },
      { path: '/me/tokens', element: <PersonalAccessTokensPage /> },
      { path: '/admin', element: <AdminPage /> },
    ],
  },
  { path: '*', element: <NotFoundPage /> },
]);
