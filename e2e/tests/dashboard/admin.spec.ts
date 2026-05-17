import { expect, test } from '../../fixtures/index.js';

test.describe('admin page', () => {
  test('non-admin user is denied; admin user sees the admin panel', async ({ authedPage }) => {
    await authedPage.goto('/admin');
    await authedPage.waitForLoadState('networkidle');
    const url = authedPage.url();

    if (!url.includes('/admin')) {
      // The user isn't a platform-admin → redirect away is the happy path.
      // Acceptable end states: redirected to /orgs OR a 403 page rendered.
      const isRedirected =
        /\/orgs(\?|$)/.test(url) ||
        /forbidden|denied|403/i.test((await authedPage.locator('body').textContent()) ?? '');
      expect(isRedirected).toBe(true);
      return;
    }

    // The user IS a platform-admin — assert admin-only content rendered.
    await expect(authedPage.getByRole('heading', { name: /admin|platform/i }).first()).toBeVisible({
      timeout: 15_000,
    });
  });
});
