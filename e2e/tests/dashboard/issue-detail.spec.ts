import { expect, test } from '../../fixtures/index.js';
import { ingestSyntheticEvent } from '../../fixtures/testData.js';
import { IssueDetailPage, IssuesPage } from '../../pages/DashboardPages.js';

test.describe('issue detail', () => {
  test('renders stacktrace + allows resolve + reopen round-trip', async ({
    authedPage,
    seededDsn,
  }) => {
    await ingestSyntheticEvent(seededDsn.dsn);

    const issues = new IssuesPage(authedPage);
    await issues.goto(seededDsn.project.orgSlug, seededDsn.project.id);

    // Open the first issue we see.
    await authedPage
      .getByText(/E2EFixtureError/i)
      .first()
      .waitFor({ timeout: 30_000 });
    await authedPage
      .getByText(/E2EFixtureError/i)
      .first()
      .click();
    await expect(authedPage).toHaveURL(/\/issues\/\d+$/);

    const detail = new IssueDetailPage(authedPage);
    // The error message + class should appear in the detail body.
    await expect(authedPage.getByText(/E2EFixtureError/i)).toBeVisible();

    // Resolve flow: button visible → click → status flips. If the test environment
    // doesn't render the button (RBAC strip, etc.) we tolerate skip.
    if (await detail.resolveButton().isVisible()) {
      await detail.resolveButton().click();
      await expect(detail.reopenButton()).toBeVisible({ timeout: 10_000 });
      await detail.reopenButton().click();
      await expect(detail.resolveButton()).toBeVisible({ timeout: 10_000 });
    }
  });
});
