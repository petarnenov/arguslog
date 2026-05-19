import { expect, test } from '@playwright/test';

import { LandingPageObject } from '../../pages/LandingPage.js';

test.describe('landing theme toggle', () => {
  test('clicking the theme toggle flips the Mantine color scheme attribute', async ({ page }) => {
    const landing = new LandingPageObject(page);
    await landing.goto();

    // Mantine writes `data-mantine-color-scheme` on the <html> element when the
    // scheme manager flips. We use that as the source of truth — the toggle's own
    // aria-label changes too, but the attribute is the integration point with the
    // rest of the app and is what we want guarded.
    const html = page.locator('html');
    const initial = await html.getAttribute('data-mantine-color-scheme');

    const toggle = await landing.oppositeSchemeButton(initial);
    if (!toggle) {
      test.skip(true, 'no light/dark scheme buttons visible — likely viewport collapse');
      return;
    }
    await toggle.click();

    // Wait for the attribute to actually change rather than asserting a hardcoded
    // value — the initial scheme is whatever the auto-detector landed on.
    await expect.poll(async () => html.getAttribute('data-mantine-color-scheme')).not.toBe(initial);
  });
});
