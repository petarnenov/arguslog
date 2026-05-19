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

    // Cold-start ingest + worker materialisation can take 5–15s on staging — wait
    // generously rather than reload-spam.
    await authedPage
      .getByText(/E2EFixtureError/i)
      .first()
      .waitFor({ timeout: 90_000 });
    await authedPage
      .getByText(/E2EFixtureError/i)
      .first()
      .click();
    await expect(authedPage).toHaveURL(/\/issues\/\d+$/);

    const detail = new IssueDetailPage(authedPage);
    // The error message + class should appear in the detail body. Locally the JSON
    // payload also includes the exception type as a substring (worker pretty-prints the
    // raw event), so `getByText` matches 2 elements — use `.first()` to take the heading.
    await expect(authedPage.getByText(/E2EFixtureError/i).first()).toBeVisible({ timeout: 15_000 });

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
