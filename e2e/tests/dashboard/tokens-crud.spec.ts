import { isRealKcAvailable } from '../../fixtures/auth.js';
import { expect, test } from '../../fixtures/index.js';
import { TokensPage } from '../../pages/DashboardPages.js';

/**
 * PAT lifecycle happy path: mint a fresh PAT via the dashboard UI, verify the plaintext is
 * surfaced once, then revoke it.
 *
 * Uses `realAuthedPage` — the OIDC blob is seeded with a REAL Keycloak JWT (password grant
 * against the `arguslog-seed` client) instead of the default PAT-as-OIDC fixture. Reason:
 * `POST /api/v1/me/tokens` deliberately rejects PAT auth (403) to prevent privilege
 * escalation — a leaked read-only PAT shouldn't be able to mint a write/admin PAT for
 * itself. JWT auth is therefore mandatory.
 *
 * On environments without a DAG-enabled KC client (staging/prod by default), the spec
 * skips itself rather than failing. Set ARGUSLOG_E2E_KC_PASSWORD_CLIENT +
 * ARGUSLOG_E2E_KC_USERNAME + ARGUSLOG_E2E_KC_PASSWORD to enable on remote.
 */
test.describe('me/tokens — create + revoke a PAT', () => {
  test.skip(
    !isRealKcAvailable(),
    'real-KC password grant not configured (set ARGUSLOG_E2E_KC_* or run against local stack)',
  );

  test('creates a PAT, surfaces plaintext once, then revokes it', async ({ realAuthedPage }) => {
    const tokens = new TokensPage(realAuthedPage);
    await tokens.goto();

    const name = `e2e-pat-crud-${Date.now().toString(36)}`;

    await tokens.nameInput().fill(name);
    // Scope toggle defaults to "all scopes" — leave it so the create succeeds without
    // having to click every checkbox in the list.
    await tokens.createTokenButton().click();

    // After successful create the page renders an `pat-issued` Alert with the plaintext
    // shown ONCE (the only time the secret leaves the server). Assert both bits.
    const issued = realAuthedPage.getByTestId('pat-issued');
    await expect(issued).toBeVisible({ timeout: 15_000 });
    const plaintext = await realAuthedPage.getByTestId('pat-plaintext').textContent();
    expect(plaintext ?? '').toMatch(/^arglog_pat_/);

    // New row in tokens-table. Use `exact: true` because the revoke ActionIcon's
    // aria-label is "Revoke <name>", which would otherwise also match the regex.
    await expect(tokens.tokensList()).toBeVisible();
    await expect(realAuthedPage.getByRole('cell', { name, exact: true })).toBeVisible({
      timeout: 5_000,
    });

    // Revoke: row revoke ActionIcon has aria-label like "Revoke <name>" (i18n key
    // tokens.revokeAria). Anchor by exact name so we never hit any other PAT row
    // (notably the long-lived `e2e-runner-local` PAT this user owns).
    await realAuthedPage.getByRole('button', { name: new RegExp(`revoke.*${name}`, 'i') }).click();
    await realAuthedPage.getByTestId('pat-revoke-confirm').click();

    // Row disappears after the mutation completes.
    await expect(realAuthedPage.getByRole('cell', { name, exact: true })).toBeHidden({
      timeout: 10_000,
    });
  });
});
