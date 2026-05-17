import { expect, test } from '../../fixtures/index.js';
import { ProjectsPage } from '../../pages/DashboardPages.js';

test.describe('projects page', () => {
  test('lists projects within an org and the seeded project shows up', async ({
    authedPage,
    seededProject,
  }) => {
    const projects = new ProjectsPage(authedPage);
    await projects.goto(seededProject.orgSlug);
    // No cached state on a fresh page mount — react-query refetches both useMyOrgs
    // and useProjects on first render. Just wait for the list to render (cold
    // staging can take ~30s for two sequential authed fetches) instead of reload-
    // spamming, which only adds more round-trips per retry.
    await expect(projects.list()).toBeVisible({ timeout: 60_000 });
    // name === slug for inputs without spaces, so the same string renders in both
    // the title <p> and the slug <code> — `.first()` keeps strict-mode happy.
    await expect(authedPage.getByText(seededProject.name).first()).toBeVisible({ timeout: 10_000 });
  });

  test('clicking a project card navigates to its issues page', async ({
    authedPage,
    seededProject,
  }) => {
    const projects = new ProjectsPage(authedPage);
    await projects.goto(seededProject.orgSlug);
    await expect(projects.list()).toBeVisible({ timeout: 60_000 });
    await authedPage.getByText(seededProject.name).first().click();
    await expect(authedPage).toHaveURL(
      new RegExp(`/orgs/${seededProject.orgSlug}/projects/${seededProject.id}/issues`),
    );
  });
});
