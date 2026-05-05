import { expect, test } from '@playwright/test';

test.describe('argus e2e — placeholder suite', () => {
  test('playwright is wired correctly', async ({ page }) => {
    await page.setContent('<title>argus-e2e-placeholder</title><h1>ok</h1>');
    await expect(page).toHaveTitle('argus-e2e-placeholder');
    await expect(page.locator('h1')).toHaveText('ok');
  });

  // Real flows (login → create project → ingest event → see issue) land in P2.
  test.skip('login → create project → ingest → issue appears', () => {});
});
