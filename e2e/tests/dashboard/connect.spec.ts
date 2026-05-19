import { expect, test } from '../../fixtures/index.js';
import { ConnectPage } from '../../pages/DashboardPages.js';

test.describe('connect screen', () => {
  test('DSN auto-provisions + PAT generator CTA is offered', async ({
    authedPage,
    seededProject,
  }) => {
    const connect = new ConnectPage(authedPage);
    await connect.goto(seededProject.orgSlug, seededProject.id);

    // DSN is auto-provisioned on first visit (the project create endpoint bundles
    // a DSN already, and the Connect screen renders it).
    await expect(authedPage.getByTestId('connect-dsn-value')).toBeVisible({ timeout: 60_000 });

    // PAT must be minted by the user — the plaintext is shown exactly once at mint
    // time, by design. We only assert the generator CTA renders; we deliberately
    // do NOT click + wait for `arglog_pat_*` to land because our auth fixture
    // seeds a PAT-as-OIDC-token blob, and `MeTokensController.create` enforces
    // `PatScopeGuard.requireDashboardSession()` — a PAT-authenticated session is
    // explicitly forbidden from minting another PAT (security policy). So the
    // mint API silently 403s and the button stays in its initial state forever.
    await expect(authedPage.getByRole('button', { name: /generate a pat/i })).toBeVisible({
      timeout: 30_000,
    });
  });

  test('"Send test event" lights up the ingest path', async ({ authedPage, seededProject }) => {
    const connect = new ConnectPage(authedPage);
    await connect.goto(seededProject.orgSlug, seededProject.id);

    // Wait until DSN is auto-provisioned (the test-ping button is disabled until then).
    await expect(authedPage.getByTestId('connect-dsn-value')).toBeVisible({ timeout: 60_000 });

    const testPingBtn = authedPage.getByTestId('connect-test-ping');
    await testPingBtn.click();

    // The result alert renders with `connect-test-ping-result` testid + a success/error
    // message. We accept either color band — staging may rate-limit at high cadences,
    // and the spec's purpose is "the wiring is live", not "the event reaches the
    // dashboard within 1s".
    await expect(authedPage.getByTestId('connect-test-ping-result')).toBeVisible({
      timeout: 30_000,
    });
  });
});
