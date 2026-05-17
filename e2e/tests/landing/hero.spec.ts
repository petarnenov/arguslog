import { expect, test } from '@playwright/test';

import { LandingPageObject } from '../../pages/LandingPage.js';

test.describe('landing hero', () => {
  test('renders hero heading + primary CTA + sign-in CTA', async ({ page }) => {
    const landing = new LandingPageObject(page);
    await landing.goto();
    await landing.expectLoaded();
    await expect(landing.signInCta()).toBeVisible();
  });

  test('primary "Get started" CTA links to the dashboard onboarding', async ({ page }) => {
    const landing = new LandingPageObject(page);
    await landing.goto();
    const cta = landing.primaryCta();
    await expect(cta).toBeVisible();
    const href = await cta.getAttribute('href');
    expect(href, 'primary CTA must point at /onboarding on the dashboard origin').toMatch(
      /\/onboarding(\?|$)/,
    );
  });
});
