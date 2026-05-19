import { expect, test } from '../../fixtures/index.js';
import { ProjectKeysPage } from '../../pages/DashboardPages.js';

/**
 * DSN lifecycle happy path through the UI. The `seededDsn` fixture already pre-provisions
 * one DSN via the API; this spec generates a SECOND key via the "Generate new key" button
 * and verifies the reveal-once secret modal renders.
 *
 * Why we don't also revoke here: org teardown (via seededOrg) cascades to project + DSN,
 * so leftover keys are cleaned automatically. Keeping the spec narrow to the generate flow
 * matches the suite's "happy paths only" rule.
 */
test.describe('project keys — generate new DSN', () => {
  test('generates a new DSN via the page CTA and surfaces the secret once', async ({
    authedPage,
    seededDsn,
  }) => {
    const keys = new ProjectKeysPage(authedPage);
    await keys.goto(seededDsn.project.orgSlug, seededDsn.project.id);
    await expect(keys.keysList()).toBeVisible({ timeout: 15_000 });

    // Initial row count for the project's DSN list. We expect it to grow by 1 after generate.
    const initialRows = await authedPage.getByTestId(/^dsn-row-/).count();

    // "Generate new key" button (i18n key projectKeys.generate). The page object's
    // createKeyButton regex already matches that copy.
    await keys.createKeyButton().click();

    // After successful generate, the page reveals the secret half of the DSN via a modal
    // with the full `arguslog://` URI shown in a Code block. Assert it surfaces and that
    // the row count grew by 1.
    await expect(authedPage.getByText(/arguslog:\/\//).first()).toBeVisible({ timeout: 15_000 });

    await expect
      .poll(async () => authedPage.getByTestId(/^dsn-row-/).count())
      .toBe(initialRows + 1);
  });
});
