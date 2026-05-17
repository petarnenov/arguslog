import { expect, test } from '@playwright/test';

import { LandingPageObject } from '../../pages/LandingPage.js';

test.describe('status page', () => {
  test('/status renders service entries', async ({ page }) => {
    const landing = new LandingPageObject(page);
    await landing.gotoStatus();

    // Page title proves we landed on the right route.
    await expect(page).toHaveTitle(/status/i);

    // Status content should mention at least one of the user-visible services.
    const body = page.locator('body');
    await expect(body).toContainText(/api|web|ingest|dashboard|services/i, { timeout: 15_000 });
  });
});
