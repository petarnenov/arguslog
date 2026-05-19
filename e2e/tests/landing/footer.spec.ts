import { expect, test } from '@playwright/test';

import { LandingPageObject } from '../../pages/LandingPage.js';

/**
 * Footer happy path. The FooterSection renders 3 links — Dashboard, Status, GitHub — plus
 * a tagline and copyright line. The Status link is the only one with a testid
 * (`footer-status-link`) since it's the canonical reachable internal route. The other
 * two are matched by exact link text to avoid colliding with the hero's "View on GitHub"
 * button.
 */
test.describe('landing — Footer', () => {
  test('renders Dashboard / Status / GitHub links and tagline', async ({ page }) => {
    const landing = new LandingPageObject(page);
    await landing.goto();
    await landing.expectLoaded();

    // Status link — anchors to the in-app `/status` route. We don't navigate here to keep
    // the spec narrow; status-page.spec.ts covers the destination.
    const statusLink = landing.footerStatusLink();
    await expect(statusLink).toBeVisible();
    expect(await statusLink.getAttribute('href')).toBe('/status');

    // GitHub link — exact-name match isolates the footer anchor from the hero's
    // "View on GitHub" button.
    const githubLink = landing.footerGithubLink();
    await expect(githubLink).toBeVisible();
    expect(await githubLink.getAttribute('href')).toContain('github.com/petarnenov/arguslog');

    // Dashboard link — points at VITE_APP_BASE_URL (env-driven). On staging it's the
    // staging dashboard URL; on local dev it's http://localhost:5173. Just assert href
    // is non-empty so the env wiring is exercised.
    const dashboardLink = landing.footerDashboardLink();
    await expect(dashboardLink).toBeVisible();
    expect(await dashboardLink.getAttribute('href')).toBeTruthy();

    // Tagline is i18n-bound to `footer.tagline` = "Less dashboard. More dialogue."
    await expect(page.getByText(/less dashboard\. more dialogue\./i)).toBeVisible();
  });

  test('Status link in footer reaches the /status page', async ({ page }) => {
    const landing = new LandingPageObject(page);
    await landing.goto();
    await landing.expectLoaded();

    await landing.footerStatusLink().click();
    await page.waitForURL(/\/status(\?|$)/);
    // StatusPage sets document.title based on overall health — same poll pattern as
    // status-page.spec.ts so we don't flake on cold-start health probes.
    await expect
      .poll(async () => page.title(), { timeout: 15_000 })
      .toMatch(/operational|degraded|down|maintenance/i);
  });
});
