import { expect, test } from '../../fixtures/index.js';
import { ProjectsPage } from '../../pages/DashboardPages.js';

test.describe('projects page', () => {
  test('lists projects within an org and the seeded project shows up', async ({
    authedPage,
    seededProject,
  }) => {
    const projects = new ProjectsPage(authedPage);
    await projects.goto(seededProject.orgSlug);
    await expect(authedPage.getByText(seededProject.name)).toBeVisible({ timeout: 15_000 });
  });

  test('clicking a project card navigates to its issues page', async ({
    authedPage,
    seededProject,
  }) => {
    const projects = new ProjectsPage(authedPage);
    await projects.goto(seededProject.orgSlug);
    await authedPage.getByText(seededProject.name).first().click();
    await expect(authedPage).toHaveURL(
      new RegExp(`/orgs/${seededProject.orgSlug}/projects/${seededProject.id}/issues`),
    );
  });
});
