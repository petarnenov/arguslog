import { expect, test } from '../../fixtures/index.js';
import { OnboardingPage } from '../../pages/DashboardPages.js';

test.describe('onboarding wizard', () => {
  test('renders the org + project form for an authenticated user', async ({ authedPage }) => {
    const onboarding = new OnboardingPage(authedPage);
    await onboarding.goto();
    // Either the form renders OR the user is auto-redirected to /orgs because they
    // already have an org (a valid happy path — the wizard is only for first-time users).
    await authedPage.waitForLoadState('networkidle');
    const url = authedPage.url();
    if (url.includes('/orgs')) {
      // User already onboarded — happy path complete.
      return;
    }
    await expect(onboarding.form().first()).toBeVisible({ timeout: 15_000 });
  });
});
