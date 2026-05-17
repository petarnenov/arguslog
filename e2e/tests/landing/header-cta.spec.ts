import { expect, test } from '@playwright/test';

import { LandingPageObject } from '../../pages/LandingPage.js';

test.describe('landing header CTAs', () => {
  test('sign-in CTA exists in the header and points at the dashboard', async ({ page }) => {
    const landing = new LandingPageObject(page);
    await landing.goto();

    const signIn = landing.signInCta();
    await expect(signIn).toBeVisible();
    const href = await signIn.getAttribute('href');
    // Sign-in either goes directly to the dashboard root (which then redirects to
    // Keycloak via RequireAuth) or to a hosted /signin route — both shapes acceptable.
    expect(href, 'sign-in CTA must point at the dashboard origin').toBeTruthy();
    expect(href).toMatch(/app\.arguslog\.org|localhost:5173|\/$/);
  });
});
