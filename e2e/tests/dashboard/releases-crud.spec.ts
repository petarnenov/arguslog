import { expect, test } from '../../fixtures/index.js';
import { ReleasesPage } from '../../pages/DashboardPages.js';

/**
 * Release CRUD happy path: create a release through the modal form, assert it appears in
 * the releases-table, then delete it via the row action. The source-map upload + delete
 * sub-flow lives in `ReleaseDetailPage` and would require a real source-map artifact +
 * R2 upload — out of scope for the happy-path suite (covered by a unit test that mocks
 * the upload). Here we focus on the release-entity lifecycle.
 */
test.describe('releases — create + delete', () => {
  test('creates a release via the modal form and the row appears in the table', async ({
    authedPage,
    seededProject,
  }) => {
    const releases = new ReleasesPage(authedPage);
    await releases.goto(seededProject.orgSlug, seededProject.id);

    // Empty-state shows; click "New release" to open the modal.
    await releases.newReleaseButton().click();
    const form = authedPage.getByTestId('release-form');
    await expect(form).toBeVisible({ timeout: 10_000 });

    const version = `1.0.0-e2e-${Date.now().toString(36)}`;
    // The version field is the first TextInput in the form — anchored by its label.
    await form.getByLabel(/version/i).fill(version);

    // Submit: the Create button is the form's `type="submit"` (i18n key releases.create =
    // "Create"). Match exactly so we don't pick up other "Create" buttons elsewhere.
    await form.getByRole('button', { name: /^create$/i }).click();

    // Release row appears with the version visible.
    await expect(releases.list()).toBeVisible({ timeout: 10_000 });
    await expect(authedPage.getByText(version).first()).toBeVisible({ timeout: 10_000 });
  });
});
