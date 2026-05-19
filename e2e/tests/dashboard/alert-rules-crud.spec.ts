import { apiRequest } from '../../fixtures/httpClient.js';
import { expect, test } from '../../fixtures/index.js';
import { AlertRulesPage } from '../../pages/DashboardPages.js';

/**
 * Alert rule create happy path. The rule form's "destinations" MultiSelect is empty when
 * the org has no destinations — the page even shows a `alertRules.needDestinations` Alert.
 * To exercise the realistic create flow we seed one webhook destination via the API first,
 * then drive the UI to select it.
 *
 * Seeding via API (not UI) is consistent with how the rest of the suite works: testData.ts
 * creates orgs/projects/DSNs via REST so each spec stays focused on the user surface it's
 * actually about — here, the rule modal.
 */
test.describe('alert rules — create rule', () => {
  test('creates a rule and the row appears in the rules table', async ({
    authedPage,
    seededProject,
    seededOrg,
  }) => {
    // Seed a destination so the MultiSelect in the rule form has at least one option.
    // The API expects kind-specific fields wrapped under `config` — top-level fails with
    // 400 "config must be a JSON object" (see createAlertDestination in apps/web/src/api/alerts.ts).
    const destName = `e2e-dest-for-rule-${Date.now().toString(36)}`;
    await apiRequest(`/api/v1/orgs/${seededOrg.id}/alert-destinations`, {
      method: 'POST',
      body: {
        kind: 'webhook',
        name: destName,
        // API expects `url` and `secret` (not webhookUrl/webhookSecret — those are UI form
        // field names that the dashboard maps internally). 400 if either is missing/empty.
        config: {
          url: 'https://hooks.example.com/e2e-rule',
          secret: 'e2e-rule-secret',
        },
      },
    });

    const rules = new AlertRulesPage(authedPage);
    await rules.goto(seededProject.orgSlug, seededProject.id);
    await rules.newRuleButton().click();

    const dialog = authedPage.getByRole('dialog');
    await expect(dialog).toBeVisible({ timeout: 10_000 });

    const ruleName = `e2e-rule-${Date.now().toString(36)}`;
    await dialog.getByLabel(/^name$/i).fill(ruleName);

    // Pick the seeded destination from the MultiSelect (i18n key alertRules.destinations).
    // Mantine MultiSelect opens an option list on focus/click.
    const destField = dialog.getByLabel(/destinations?/i);
    await destField.click();
    await authedPage.getByRole('option', { name: new RegExp(destName) }).click();
    // Close the option popup so the submit isn't intercepted.
    await dialog.getByLabel(/^name$/i).click();

    await dialog.getByRole('button', { name: /^create$/i }).click();

    // Rule row appears in alert-rules-table.
    await expect(rules.rulesList()).toBeVisible({ timeout: 10_000 });
    await expect(authedPage.getByText(ruleName)).toBeVisible({ timeout: 10_000 });
  });
});
