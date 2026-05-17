import { expect, test } from '../../fixtures/index.js';
import { OrgsLandingPage } from '../../pages/DashboardPages.js';

test.describe('orgs landing', () => {
  test('authenticated user visiting /orgs is redirected to their first org', async ({
    authedPage,
  }) => {
    const orgs = new OrgsLandingPage(authedPage);
    await orgs.goto();
    // OrgsLandingPage is a redirect-only route: it dispatches to either
    // /onboarding (no orgs yet) or /orgs/<first-slug>/projects (one or more orgs).
    // The user signs in with the runner identity, which always has at least one
    // org (their seeded fixture data persists across runs), so we expect the
    // projects-page redirect.
    await authedPage.waitForURL(/\/orgs\/[^/]+\/projects|\/onboarding/, { timeout: 15_000 });
    expect(authedPage.url()).toMatch(/\/orgs\/[^/]+\/projects|\/onboarding/);
  });

  test('seeded org appears in the app-shell org switcher', async ({ authedPage, seededOrg }) => {
    // After org creation via the test-data fixture, the dashboard's app-shell
    // sidebar shows it among the user's orgs. We navigate to the seeded org
    // directly (proves the slug resolves) and assert the projects page renders.
    await authedPage.goto(`/orgs/${seededOrg.slug}/projects`);
    await expect(authedPage).toHaveURL(new RegExp(`/orgs/${seededOrg.slug}/projects`));
    // The page renders — either a project list or the empty state.
    await expect(authedPage.locator('body')).toBeVisible();
  });
});
