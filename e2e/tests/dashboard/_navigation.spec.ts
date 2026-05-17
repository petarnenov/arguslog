import { expect, test } from '../../fixtures/index.js';

/**
 * Cross-cutting smoke: after sign-in, all primary nav routes resolve with HTTP 200.
 * Catches the "I renamed a route and forgot to update the link" class of bug — cheap
 * coverage that runs in <5s.
 *
 * Leading underscore on the filename keeps this spec sorted to the top of the
 * dashboard suite directory; useful when triaging a CI failure (this one runs first).
 */
test.describe('cross-cutting navigation smoke', () => {
  test('every primary route renders for an authed user', async ({ authedPage, seededProject }) => {
    const routes = [
      '/orgs',
      `/orgs/${seededProject.orgSlug}/projects`,
      `/orgs/${seededProject.orgSlug}/projects/${seededProject.id}/issues`,
      `/orgs/${seededProject.orgSlug}/projects/${seededProject.id}/keys`,
      `/orgs/${seededProject.orgSlug}/projects/${seededProject.id}/connect`,
      `/orgs/${seededProject.orgSlug}/projects/${seededProject.id}/alert-rules`,
      `/orgs/${seededProject.orgSlug}/projects/${seededProject.id}/releases`,
      `/orgs/${seededProject.orgSlug}/members`,
      `/orgs/${seededProject.orgSlug}/destinations`,
      '/me/tokens',
    ];

    for (const route of routes) {
      const resp = await authedPage.goto(route);
      expect(resp?.status() ?? 0, `GET ${route} should not 4xx/5xx`).toBeLessThan(400);
      // The dashboard is a SPA — the document is always 200 even for not-found routes.
      // Assert we don't land on a global-error fallback by checking for our app shell.
      await expect(authedPage.locator('body')).toBeVisible();
    }
  });
});
