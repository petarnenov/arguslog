import { createBrowserRouter, Navigate } from 'react-router';

import { RequireAuth } from './auth/RequireAuth';
import { AppShellLayout } from './layouts/AppShellLayout';
import { AdminPage } from './pages/AdminPage';
import { AlertDestinationsPage } from './pages/AlertDestinationsPage';
import { AlertRulesPage } from './pages/AlertRulesPage';
import { AuthCallbackPage } from './pages/AuthCallbackPage';
import { BillingPage } from './pages/BillingPage';
import { IssueDetailPage } from './pages/IssueDetailPage';
import { IssuesPage } from './pages/IssuesPage';
import { LoginPage } from './pages/LoginPage';
import { NotFoundPage } from './pages/NotFoundPage';
import { OnboardingPage } from './pages/OnboardingPage';
import { OrgSettingsPage } from './pages/OrgSettingsPage';
import { OrgsLandingPage } from './pages/OrgsLandingPage';
import { PersonalAccessTokensPage } from './pages/PersonalAccessTokensPage';
import { ProjectKeysPage } from './pages/ProjectKeysPage';
import { ProjectSettingsPage } from './pages/ProjectSettingsPage';
import { ProjectsPage } from './pages/ProjectsPage';
import { ReleasesPage } from './pages/ReleasesPage';
import { UserBillingPage } from './pages/UserBillingPage';

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
      { path: '/orgs', element: <OrgsLandingPage /> },
      { path: '/orgs/:orgSlug/projects', element: <ProjectsPage /> },
      { path: '/orgs/:orgSlug/projects/:projectId/issues', element: <IssuesPage /> },
      {
        path: '/orgs/:orgSlug/projects/:projectId/issues/:issueId',
        element: <IssueDetailPage />,
      },
      { path: '/orgs/:orgSlug/projects/:projectId/settings', element: <ProjectSettingsPage /> },
      { path: '/orgs/:orgSlug/projects/:projectId/settings/keys', element: <ProjectKeysPage /> },
      { path: '/orgs/:orgSlug/projects/:projectId/alert-rules', element: <AlertRulesPage /> },
      { path: '/orgs/:orgSlug/projects/:projectId/releases', element: <ReleasesPage /> },
      { path: '/orgs/:orgSlug/settings', element: <OrgSettingsPage /> },
      { path: '/orgs/:orgSlug/settings/destinations', element: <AlertDestinationsPage /> },
      { path: '/orgs/:orgSlug/billing', element: <BillingPage /> },
      { path: '/me/tokens', element: <PersonalAccessTokensPage /> },
      { path: '/billing', element: <UserBillingPage /> },
      { path: '/admin', element: <AdminPage /> },
    ],
  },
  { path: '*', element: <NotFoundPage /> },
]);
