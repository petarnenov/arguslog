import { expect, test } from '@playwright/test';

import { LandingPageObject } from '../../pages/LandingPage.js';

/**
 * Features section happy path. The section is i18n-driven (apps/landing/src/locales/en.json
 * → `features.items.<key>.title`), so this spec anchors on the literal English copy of each
 * card title. If the marketing copy changes, both en.json and these expectations move
 * together — there's no testid hook because the cards are generated from an icon map and
 * adding testids per item would be noise.
 */
test.describe('landing — Features section', () => {
  test('renders all 7 feature cards with their titles', async ({ page }) => {
    const landing = new LandingPageObject(page);
    await landing.goto();
    await landing.expectLoaded();

    await expect(landing.featuresHeading()).toBeVisible();

    // The cards come from FEATURE_ICONS keys (ingest/sourcemaps/breadcrumbs/web3/alerts/
    // slackInbound/team). The exact strings live in en.json; assert each one shows up.
    const expectedTitles = [
      /real-time ingest/i,
      /source-mapped stack traces/i,
      /auto-breadcrumb timeline/i,
      /web3 — first-class/i,
      /multi-channel alerts/i,
      /triage in slack/i,
      /team workflows/i,
    ];
    for (const title of expectedTitles) {
      await expect(
        landing.featureCardTitle(title),
        `feature card "${title}" must render`,
      ).toBeVisible();
    }
  });
});
