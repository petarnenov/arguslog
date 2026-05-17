import { expect, test } from '../../fixtures/index.js';
import { OrgsLandingPage } from '../../pages/DashboardPages.js';

test.describe('orgs landing', () => {
  test('authenticated user lands on /orgs and sees their orgs', async ({
    authedPage,
    seededOrg,
  }) => {
    const orgs = new OrgsLandingPage(authedPage);
    await orgs.goto();

    // Either the grid renders (existing orgs) or an empty state — both shapes
    // exist depending on whether the test user already has orgs. The seeded
    // org guarantees at least one will be visible.
    await expect(authedPage.getByText(seededOrg.name)).toBeVisible({ timeout: 15_000 });
  });

  test('clicking an org card navigates to its projects', async ({ authedPage, seededOrg }) => {
    const orgs = new OrgsLandingPage(authedPage);
    await orgs.goto();
    await authedPage.getByText(seededOrg.name).first().click();
    await expect(authedPage).toHaveURL(new RegExp(`/orgs/${seededOrg.slug}/projects`));
  });
});
