import { expect, test } from '@playwright/test';

import { LandingPageObject } from '../../pages/LandingPage.js';

/**
 * MCP section happy path. The section is the product's main differentiator — "Designed
 * for agents". It renders:
 *   - heading + subheading
 *   - two cards (Install in 30 seconds + Full API coverage)
 *   - a Code block with a sample Claude Desktop MCP config
 *   - two anchor links: npm package + docs
 *
 * We assert the heading, the npm/docs anchors, and that the config snippet contains the
 * literal "arguslog" server name so we know the snippet rendered.
 */
test.describe('landing — MCP section', () => {
  test('renders heading, config snippet, and external links', async ({ page }) => {
    const landing = new LandingPageObject(page);
    await landing.goto();
    await landing.expectLoaded();

    await expect(landing.mcpHeading()).toBeVisible();

    // Sub-card titles from i18n keys mcp.bullet1Title / mcp.bullet2Title.
    await expect(page.getByRole('heading', { name: /install in 30 seconds/i })).toBeVisible();
    await expect(
      page.getByRole('heading', { name: /full api coverage out of the box/i }),
    ).toBeVisible();

    // Config snippet — assert literal `@arguslog/mcp-server` is on the page so we know the
    // Code block was rendered (not just an empty container).
    await expect(page.getByText('@arguslog/mcp-server').first()).toBeVisible();

    // External links: npm + docs. Use exact link text so we don't pick up unrelated anchors.
    const npmLink = page.getByRole('link', { name: /view on npm/i });
    await expect(npmLink).toBeVisible();
    expect(await npmLink.getAttribute('href')).toContain('npmjs.com');

    const docsLink = page.getByRole('link', { name: /read the docs on github/i });
    await expect(docsLink).toBeVisible();
    expect(await docsLink.getAttribute('href')).toContain('github.com/petarnenov/arguslog');
  });
});
