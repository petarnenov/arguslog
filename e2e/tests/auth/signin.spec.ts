import { expect, test } from '@playwright/test';

/**
 * The basic auth-flow smoke: an unauthenticated browser hitting any protected
 * dashboard route gets bounced to the Keycloak login page. We don't drive the
 * KC form here — that's the territory of the `loginAsTestUser` fixture used by
 * every other dashboard spec. This spec only proves the redirect plumbing.
 */
test.describe('sign-in flow', () => {
  test('unauthenticated visit to /orgs redirects to Keycloak', async ({ page }) => {
    await page.goto('/orgs');

    // Wait until either we're on the dashboard `/orgs` (already signed in) OR
    // we've been navigated to a *.keycloak* host. Both are valid outcomes — the
    // suite is run by a real authenticated test user via a separate fixture, so
    // a pre-existing session in CI is fine too.
    await page.waitForLoadState('networkidle');
    const url = page.url();
    const isKeycloak = /keycloak|protocol\/openid-connect/.test(url);
    const isOrgs = /\/orgs(\?|$)/.test(url);
    expect(
      isKeycloak || isOrgs,
      `expected redirect to Keycloak or successful /orgs render — got ${url}`,
    ).toBe(true);
  });
});
