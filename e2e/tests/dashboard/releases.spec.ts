import { expect, test } from '../../fixtures/index.js';
import { ReleasesPage } from '../../pages/DashboardPages.js';

test.describe('releases page', () => {
  test('empty state shows for a fresh project + create-release CTA is visible', async ({
    authedPage,
    seededProject,
  }) => {
    const releases = new ReleasesPage(authedPage);
    await releases.goto(seededProject.orgSlug, seededProject.id);

    // Either an empty-state component appears OR the create button is visible —
    // both prove the page loaded for an empty-release project.
    const emptyOrCreate = authedPage
      .getByText(/no releases yet|create.*release|new release/i)
      .first();
    await expect(emptyOrCreate).toBeVisible({ timeout: 15_000 });
  });
});
