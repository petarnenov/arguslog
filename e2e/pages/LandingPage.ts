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

  /**
   * The header's theme switcher is an `ActionIcon.Group` of THREE buttons — Light / Auto /
   * Dark (apps/landing/src/components/ThemeToggle.tsx). Each is an `ActionIcon` with
   * `aria-label={schemeLabel}` and `aria-pressed=true` on the currently-active option.
   *
   * Returns the button for the EXPLICIT opposite of the current scheme (light → dark,
   * dark → light). Avoids `Auto` because Mantine resolves it back through the system
   * preference — in headless Chrome that's "light" too, so clicking Auto can leave
   * `data-mantine-color-scheme` unchanged.
   *
   * Returns null only if neither Light nor Dark is visible (true viewport collapse) —
   * callers should fall back to `test.skip` in that case.
   */
  async oppositeSchemeButton(
    currentScheme: string | null,
  ): Promise<ReturnType<Page['getByRole']> | null> {
    // Normalise. Anything that isn't explicitly "dark" we treat as "light-ish" so the
    // toggle target is Dark — which always produces a visible flip.
    const target = currentScheme === 'dark' ? 'Light' : 'Dark';
    const btn = this.page.getByRole('button', { name: target, exact: true });
    return (await btn.isVisible()) ? btn : null;
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

  /**
   * Heading of the Features section ("Everything you need, nothing you don't."). The page
   * has no testid here, so we anchor on the literal copy from en.json — if that changes,
   * update both at once.
   */
  featuresHeading() {
    return this.page.getByRole('heading', { name: /everything you need/i });
  }

  /** Heading of one feature card (title from `features.items.<key>.title`). */
  featureCardTitle(text: string | RegExp) {
    return this.page.getByRole('heading', { name: text });
  }

  agentInstallBadge(text: string) {
    return this.page.getByText(text, { exact: true });
  }

  agentInstallCta() {
    return this.page.getByRole('link', { name: /start in 3 seconds/i });
  }

  mcpHeading() {
    return this.page.getByRole('heading', { name: /mcp-first by design/i });
  }

  /** Footer status link rendered by `FooterSection` (data-testid="footer-status-link"). */
  footerStatusLink() {
    return this.page.getByTestId('footer-status-link');
  }

  /**
   * The footer renders the GitHub anchor with literal text "GitHub". The HEADER also renders a
   * "GitHub" link (i18n key nav.github), so an exact-name match would collide → strict-mode
   * violation. We pick the LAST one — the page is short and the footer is always at the bottom.
   * (`footer-status-link` is the only adjacent footer link with a testid, so we don't have a
   * cleaner anchor to scope by.)
   */
  footerGithubLink() {
    return this.page.getByRole('link', { name: 'GitHub', exact: true }).last();
  }

  footerDashboardLink() {
    return this.page.getByRole('link', { name: 'Dashboard', exact: true }).last();
  }

  async expectLoaded(): Promise<void> {
    await expect(this.hero()).toBeVisible();
    await expect(this.primaryCta()).toBeVisible();
  }
}
