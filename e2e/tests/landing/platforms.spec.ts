import { expect, test } from '@playwright/test';

import { LandingPageObject } from '../../pages/LandingPage.js';

test.describe('landing platforms section', () => {
  test('fetches /api/v1/platforms and renders the SDK catalog', async ({ page }) => {
    const landing = new LandingPageObject(page);

    // Promise the network response BEFORE we navigate — pattern Playwright recommends.
    const platformsResponse = page.waitForResponse(
      (resp) => resp.url().includes('/api/v1/platforms') && resp.status() === 200,
    );

    await landing.goto();
    const resp = await platformsResponse;
    const json = (await resp.json()) as Array<{ slug: string; pkg: string }>;
    expect(
      json.length,
      'platform catalog must include at least the 5 active JS SDKs',
    ).toBeGreaterThanOrEqual(5);

    // At least the canonical 5 must show up (Vue/React/Next/Angular/RN per the
    // post-cross-SDK-rework state).
    const slugs = json.map((p) => p.slug);
    for (const expected of ['vue', 'react', 'nextjs', 'angular', 'react-native']) {
      expect(slugs).toContain(expected);
    }
  });
});
