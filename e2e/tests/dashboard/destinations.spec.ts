import { expect, test } from '../../fixtures/index.js';
import { AlertDestinationsPage } from '../../pages/DashboardPages.js';

test.describe('alert destinations page', () => {
  test('loads + shows the create-destination CTA for a fresh org', async ({
    authedPage,
    seededOrg,
  }) => {
    const destinations = new AlertDestinationsPage(authedPage);
    await destinations.goto(seededOrg.slug);
    const headingOrCta = authedPage
      .getByRole('heading', { name: /destinations?/i })
      .or(authedPage.getByRole('button', { name: /new destination|add destination/i }))
      .first();
    await expect(headingOrCta).toBeVisible({ timeout: 15_000 });
  });
});
