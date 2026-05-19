import { apiRequest } from '../../fixtures/httpClient.js';
import { expect, test } from '../../fixtures/index.js';
import { OnboardingPage } from '../../pages/DashboardPages.js';

/**
 * Onboarding wizard SUBMIT happy path — distinct from the existing `onboarding.spec.ts`
 * which only checks the form renders. Here we actually drive the form to completion and
 * assert the success modal (containing the freshly-minted DSN) appears.
 *
 * The wizard only renders for users with zero orgs — for everyone else `/onboarding`
 * redirects to `/orgs/<first>/projects`. Per the existing onboarding spec pattern, we
 * skip when the redirect happens (a valid happy path: user is already onboarded).
 *
 * Cleanup: the created org is NOT owned by any `seededOrg` fixture (the wizard uses the
 * UI's own create path), so we must DELETE it explicitly in afterEach. Without this,
 * users on the `regular` tier (1-org cap, e.g. the staging demo user) get 402 on every
 * subsequent `seededOrg` create — the whole suite cascades into failure.
 */
test.describe('onboarding wizard — submit', () => {
  // Track orgs created by this spec so the afterEach knows what to delete. A single
  // value would suffice but the array form is more honest about lifecycle.
  let createdOrgSlug: string | null = null;

  test.afterEach(async () => {
    if (!createdOrgSlug) return;
    // Look the org up by slug since the UI doesn't surface the id in a stable place.
    const orgs = await apiRequest<{ id: number; slug: string }[]>('/api/v1/orgs');
    const match = orgs.find((o) => o.slug === createdOrgSlug);
    if (match) {
      try {
        await apiRequest(`/api/v1/orgs/${match.id}`, { method: 'DELETE' });
      } catch (err) {
        console.warn(`onboarding-submit teardown: deleteOrg(${match.id}) failed:`, err);
      }
    }
    createdOrgSlug = null;
  });

  test('fills org + project form and reaches the success modal with DSN', async ({
    authedPage,
  }) => {
    const onboarding = new OnboardingPage(authedPage);
    await onboarding.goto();

    // If the user already has an org, /onboarding redirects to /orgs/<slug>/projects.
    // That's a valid happy path — nothing to submit. Skip rather than fail.
    await authedPage.waitForLoadState('networkidle');
    if (!authedPage.url().endsWith('/onboarding')) {
      test.skip(true, 'runner already has an org — onboarding form does not render');
      return;
    }

    await expect(onboarding.form().first()).toBeVisible({ timeout: 15_000 });

    const orgName = `e2e-onb-org-${Date.now().toString(36)}`;
    const projectName = `e2e-onb-proj-${Date.now().toString(36)}`;
    // Slug is server-derived but for inputs with no spaces / special chars it matches
    // the name verbatim. Track for afterEach cleanup.
    createdOrgSlug = orgName;

    await onboarding.orgNameInput().fill(orgName);
    await onboarding.projectNameInput().fill(projectName);
    await onboarding.submitButton().click();

    // The success Modal renders a Code block with the freshly-minted DSN. The title
    // comes from i18n key onboarding.successTitle. Assert it shows up; we don't open
    // the DSN value to keep the spec narrow — connect.spec.ts covers DSN visibility.
    await expect(authedPage.getByRole('dialog')).toBeVisible({ timeout: 20_000 });
    await expect(authedPage.getByText(/arguslog:\/\//)).toBeVisible({ timeout: 5_000 });
  });
});
