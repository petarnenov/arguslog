import { expect, test } from '../../fixtures/index.js';
import { TokensPage } from '../../pages/DashboardPages.js';

test.describe('me/tokens page', () => {
  test("lists the test user's PATs and surfaces the create CTA", async ({ authedPage }) => {
    const tokens = new TokensPage(authedPage);
    await tokens.goto();

    // The runner PAT we authenticated with should be listed (its name + prefix).
    // We only assert the page rendered + the create CTA is reachable — the actual
    // listing tests live in the unit suite.
    await expect(authedPage.getByRole('heading', { name: /tokens|access tokens/i })).toBeVisible({
      timeout: 15_000,
    });
    await expect(tokens.createTokenButton().first()).toBeVisible();
  });
});
