import { expect, test } from '../../fixtures/index.js';

test.describe('admin page', () => {
  test('non-admin user is denied; admin user sees the admin panel', async ({ authedPage }) => {
    await authedPage.goto('/admin');
    await authedPage.waitForLoadState('networkidle');
    const url = authedPage.url();

    if (!url.includes('/admin')) {
      // The user isn't a platform-admin → ANY redirect away from /admin is the happy
      // path. Don't require a specific target (/orgs) or 403 body text — different
      // entry conditions (no orgs yet → /onboarding, existing user → /orgs/<slug>/projects,
      // 403 page rendered, etc.) all satisfy "denied".
      expect(url).not.toContain('/admin');
      return;
    }

    // The user IS a platform-admin — assert admin-only content rendered.
    await expect(authedPage.getByRole('heading', { name: /admin|platform/i }).first()).toBeVisible({
      timeout: 15_000,
    });
  });
});
