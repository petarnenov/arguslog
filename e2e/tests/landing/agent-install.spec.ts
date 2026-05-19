import { expect, test } from '@playwright/test';

import { LandingPageObject } from '../../pages/LandingPage.js';

/**
 * Agent-install section happy path. This is the "3-second install" pitch directly under
 * the hero — `AGENT_INSTALL_LIST` in LandingPage.tsx renders 6 agent badges (Claude Code,
 * Cursor, Codex, GitHub Copilot, Windsurf, Continue). The 3-step Card grid uses
 * step1/step2/step3 i18n keys; we assert the headings and the closing CTA.
 */
const AGENTS = ['Claude Code', 'Cursor', 'Codex', 'GitHub Copilot', 'Windsurf', 'Continue'];

test.describe('landing — Agent Install section', () => {
  test('renders 3-second-install pitch with all 6 agent badges and CTA', async ({ page }) => {
    const landing = new LandingPageObject(page);
    await landing.goto();
    await landing.expectLoaded();

    // Section heading from i18n key `agentInstall.heading`.
    await expect(
      page.getByRole('heading', { name: /install arguslog with your ai agent/i }),
    ).toBeVisible();

    // 3 step cards — assert each step number-prefixed title is visible.
    await expect(page.getByText(/^1\.\s*sign up/i)).toBeVisible();
    await expect(page.getByText(/^2\.\s*open connect/i)).toBeVisible();
    await expect(page.getByText(/^3\.\s*paste into your agent/i)).toBeVisible();

    // Each agent badge must be visible. Use `getByText` exact so "Cursor" doesn't accidentally
    // match other copy that contains the word "cursor".
    for (const agent of AGENTS) {
      await expect(
        landing.agentInstallBadge(agent),
        `agent badge "${agent}" must render`,
      ).toBeVisible();
    }

    // Bottom CTA — links to dashboard onboarding (same target as the hero CTA).
    const cta = landing.agentInstallCta();
    await expect(cta).toBeVisible();
    expect(await cta.getAttribute('href')).toMatch(/\/onboarding(\?|$)/);
  });
});
