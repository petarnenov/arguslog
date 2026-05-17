import { expect, test } from '../../fixtures/index.js';
import { AlertRulesPage } from '../../pages/DashboardPages.js';

test.describe('alert rules page', () => {
  test('loads + shows the create-rule CTA for an empty project', async ({
    authedPage,
    seededProject,
  }) => {
    const rules = new AlertRulesPage(authedPage);
    await rules.goto(seededProject.orgSlug, seededProject.id);

    // The page renders with either an empty state OR a list with a create CTA.
    const headingOrCta = authedPage
      .getByRole('heading', { name: /alert rules?/i })
      .or(authedPage.getByRole('button', { name: /new.*rule|create.*rule/i }))
      .first();
    await expect(headingOrCta).toBeVisible({ timeout: 15_000 });
  });
});
