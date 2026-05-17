import { expect, test } from '@playwright/test';

import { LandingPageObject } from '../../pages/LandingPage.js';

test.describe('status page', () => {
  test('/status renders service entries', async ({ page }) => {
    const landing = new LandingPageObject(page);
    await landing.gotoStatus();

    // StatusPage sets `document.title` based on overall health ("Operational —
    // Arguslog" / "Degraded — Arguslog" / "Down — Arguslog"). Wait for the
    // SPA-side title-set rather than asserting the initial HTML title which is
    // the generic landing title.
    await expect
      .poll(async () => page.title(), { timeout: 15_000 })
      .toMatch(/operational|degraded|down|maintenance/i);

    // Status content should mention at least one of the user-visible services.
    const body = page.locator('body');
    await expect(body).toContainText(/api|web|ingest|dashboard|services/i, { timeout: 15_000 });
  });
});
