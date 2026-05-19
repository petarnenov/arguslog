import { expect, test } from '../../fixtures/index.js';
import { AlertDestinationsPage } from '../../pages/DashboardPages.js';

/**
 * Alert destination CRUD happy path: pick the webhook kind (no external service needed —
 * any URL is accepted by the API; the worker will fail to dispatch later but the create
 * itself is a clean DB write), fill name + url + secret, submit, assert the row renders
 * in the destinations table.
 *
 * We don't exercise telegram/email/slack/github kinds here — each has its own validation
 * shape and would require external secrets. Webhook is the lowest-friction happy path.
 */
test.describe('alert destinations — create webhook', () => {
  test('creates a webhook destination through the modal form', async ({
    authedPage,
    seededOrg,
  }) => {
    const destinations = new AlertDestinationsPage(authedPage);
    await destinations.goto(seededOrg.slug);

    await destinations.newDestinationButton().click();
    const dialog = authedPage.getByRole('dialog');
    await expect(dialog).toBeVisible({ timeout: 10_000 });

    // Select kind = webhook. The Select uses Mantine's combobox — click to open then
    // pick the option by label.
    await dialog.getByLabel(/kind/i).click();
    await authedPage.getByRole('option', { name: /webhook/i }).click();

    const name = `e2e-dest-webhook-${Date.now().toString(36)}`;
    await dialog.getByLabel(/name/i).fill(name);
    await dialog.getByLabel(/webhook url/i).fill('https://hooks.example.com/e2e');
    // The HMAC secret PasswordInput is labelled "HMAC secret (optional)" — i18n key
    // alertDestinations.webhookSecret. Match both that and any future rename to "Webhook
    // secret" so the spec doesn't break on copy tweaks.
    await dialog.getByLabel(/hmac secret|webhook secret/i).fill('e2e-secret-not-used');

    await dialog.getByRole('button', { name: /^create$/i }).click();

    // Row appears in the destinations table.
    await expect(destinations.destinationsList()).toBeVisible({ timeout: 10_000 });
    await expect(authedPage.getByText(name)).toBeVisible({ timeout: 10_000 });
  });
});
