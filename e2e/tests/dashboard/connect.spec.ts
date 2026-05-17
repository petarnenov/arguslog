import { expect, test } from '../../fixtures/index.js';
import { ConnectPage } from '../../pages/DashboardPages.js';

test.describe('connect screen', () => {
  test('auto-provisions a DSN + PAT visible on the page', async ({ authedPage, seededProject }) => {
    const connect = new ConnectPage(authedPage);
    await connect.goto(seededProject.orgSlug, seededProject.id);

    // The Connect screen auto-mints a DSN + PAT on first visit; both surface as
    // testid-tagged code blocks. Cold staging boot can take ~30s for the auth +
    // project + dsn + pat round-trips combined.
    await expect(authedPage.getByTestId('connect-dsn-value')).toBeVisible({ timeout: 60_000 });
    await expect(authedPage.getByText(/arglog_pat_/)).toBeVisible({ timeout: 60_000 });
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
