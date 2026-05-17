/**
 * Page Object for `https://arguslog.org` (the public landing page). Tests use these
 * named methods so changes to a button label / DOM structure flow to one spot.
 */
import { type Page, expect } from '@playwright/test';

export class LandingPageObject {
  constructor(public readonly page: Page) {}

  async goto(): Promise<void> {
    await this.page.goto('/');
  }

  async gotoStatus(): Promise<void> {
    await this.page.goto('/status');
  }

  hero() {
    return this.page.getByRole('heading', { level: 1 });
  }

  primaryCta() {
    // The hero CTA copy is „Get started" — uses a Mantine Button rendered as an anchor.
    return this.page.getByRole('link', { name: /get started/i }).first();
  }

  signInCta() {
    return this.page.getByRole('link', { name: /sign in/i }).first();
  }

  async themeToggle() {
    // Header theme toggle — Mantine ActionIcon with aria-label.
    return this.page.getByRole('button', { name: /switch to (dark|light) mode/i });
  }

  platformsSection() {
    return this.page.getByTestId('landing-platforms');
  }

  /** Returns the visible platform card titles, filtering loading skeletons. */
  async platformNames(): Promise<string[]> {
    const cards = this.page.getByTestId(/^landing-platform-card-/);
    const count = await cards.count();
    const names: string[] = [];
    for (let i = 0; i < count; i++) {
      const heading = cards.nth(i).getByRole('heading');
      if (await heading.isVisible()) names.push((await heading.textContent()) ?? '');
    }
    return names.filter(Boolean);
  }

  async expectLoaded(): Promise<void> {
    await expect(this.hero()).toBeVisible();
    await expect(this.primaryCta()).toBeVisible();
  }
}
