import { createBrowserRouter, Navigate } from 'react-router';

import { AppShellLayout } from './layouts/AppShellLayout';
import { AdminPage } from './pages/AdminPage';
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
  { path: '/onboarding', element: <OnboardingPage /> },
  {
    element: <AppShellLayout />,
    children: [
      { path: '/orgs', element: <Navigate to="/login" replace /> },
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
