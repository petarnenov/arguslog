import { createBrowserRouter, Navigate } from 'react-router';

import { RequireAuth } from './auth/RequireAuth';
import { AppShellLayout } from './layouts/AppShellLayout';
import { AdminPage } from './pages/AdminPage';
import { AuthCallbackPage } from './pages/AuthCallbackPage';
import { IssueDetailPage } from './pages/IssueDetailPage';
import { IssuesPage } from './pages/IssuesPage';
import { LoginPage } from './pages/LoginPage';
import { NotFoundPage } from './pages/NotFoundPage';
import { OnboardingPage } from './pages/OnboardingPage';
import { OrgSettingsPage } from './pages/OrgSettingsPage';
import { ProjectKeysPage } from './pages/ProjectKeysPage';
import { ProjectSettingsPage } from './pages/ProjectSettingsPage';
import { ProjectsPage } from './pages/ProjectsPage';

export const router = createBrowserRouter([
  { path: '/', element: <Navigate to="/orgs" replace /> },
  { path: '/login', element: <LoginPage /> },
  { path: '/auth/callback', element: <AuthCallbackPage /> },
  {
    element: (
      <RequireAuth>
        <AppShellLayout />
      </RequireAuth>
    ),
    children: [
      { path: '/onboarding', element: <OnboardingPage /> },
      { path: '/orgs', element: <Navigate to="/onboarding" replace /> },
      { path: '/orgs/:orgSlug/projects', element: <ProjectsPage /> },
      { path: '/orgs/:orgSlug/projects/:projectSlug/issues', element: <IssuesPage /> },
      {
        path: '/orgs/:orgSlug/projects/:projectSlug/issues/:issueId',
        element: <IssueDetailPage />,
      },
      { path: '/orgs/:orgSlug/projects/:projectSlug/settings', element: <ProjectSettingsPage /> },
      { path: '/orgs/:orgSlug/projects/:projectSlug/settings/keys', element: <ProjectKeysPage /> },
      { path: '/orgs/:orgSlug/settings', element: <OrgSettingsPage /> },
      { path: '/admin', element: <AdminPage /> },
    ],
  },
  { path: '*', element: <NotFoundPage /> },
]);
