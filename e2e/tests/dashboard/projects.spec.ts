import { expect, test } from '../../fixtures/index.js';
import { ProjectsPage } from '../../pages/DashboardPages.js';

test.describe('projects page', () => {
  test('lists projects within an org and the seeded project shows up', async ({
    authedPage,
    seededProject,
  }) => {
    const projects = new ProjectsPage(authedPage);
    await projects.goto(seededProject.orgSlug);
    // The dashboard's react-query may have cached the org's projects list
    // BEFORE the fixture created `seededProject` — give the page a moment +
    // one reload to re-fetch fresh data after the test-data setup.
    await expect
      .poll(
        async () => {
          await authedPage.reload();
          return authedPage.getByText(seededProject.name).isVisible();
        },
        { timeout: 30_000, intervals: [2_000, 3_000, 5_000] },
      )
      .toBe(true);
  });

  test('clicking a project card navigates to its issues page', async ({
    authedPage,
    seededProject,
  }) => {
    const projects = new ProjectsPage(authedPage);
    await projects.goto(seededProject.orgSlug);
    await expect
      .poll(
        async () => {
          await authedPage.reload();
          return authedPage.getByText(seededProject.name).isVisible();
        },
        { timeout: 30_000, intervals: [2_000, 3_000, 5_000] },
      )
      .toBe(true);
    await authedPage.getByText(seededProject.name).first().click();
    await expect(authedPage).toHaveURL(
      new RegExp(`/orgs/${seededProject.orgSlug}/projects/${seededProject.id}/issues`),
    );
  });
});
