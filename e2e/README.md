# @arguslog/e2e

End-to-end Playwright suite for the Arguslog dashboard (`app.arguslog.org`) and landing
page (`arguslog.org`). Runs against live staging on every merge to `main` via
`.github/workflows/e2e-staging.yml`; can also run locally against `make demo` or any
self-hosted Arguslog stack.

---

## What this suite covers

**Happy paths only** — error / validation / permission-denied coverage lives in the
unit suite (`apps/web/src/__tests__/`). This suite proves the primary user flows work
end-to-end against a real backend.

- **Landing** (`tests/landing/`, 5 specs): hero CTA, platforms catalog API, status
  page, header CTAs, theme toggle.
- **Auth** (`tests/auth/`, 1 spec): unauthenticated visit redirects to Keycloak.
- **Dashboard** (`tests/dashboard/`, 14 specs): one spec per route — onboarding,
  orgs landing, projects, issues, issue detail, Connect screen (DSN/PAT + test
  event), keys, releases, alert rules, alert destinations, members, tokens, admin,
  cross-cutting navigation smoke.

Every authenticated test creates a fresh org (and optionally project + DSN) named
`e2e-<run-id>-<hash>`, runs against that isolated data, then `DELETE`s the org on
teardown (cascades to projects, DSNs, alert rules, releases, members).

---

## Running locally

### Against staging

You need ONE secret — minted once on staging and exported as an env var:

```bash
export ARGUSLOG_E2E_RUNNER_PAT=arglog_pat_<plaintext>
```

Then:

```bash
cd e2e
pnpm install
pnpm exec playwright install chromium
pnpm test:staging
# or filter
pnpm test:staging --grep landing
pnpm test:staging --grep "connect screen"
# or open the Playwright UI
pnpm exec playwright test --ui
```

### Against `make demo` (full local stack)

```bash
# Terminal 1: bring the stack up
make demo

# Terminal 2: mint a PAT for the demo user (or any local user) via the
# dashboard's /me/tokens UI, then:
export ARGUSLOG_E2E_RUNNER_PAT=<arglog_pat_…>
cd e2e
pnpm test:local
```

---

## One-time staging-side setup

**Just one thing**: mint a long-lived PAT on staging for the E2E user and store its
plaintext in `E2E_RUNNER_PAT` secret. The auth fixture seeds an OIDC user blob into
the page's `localStorage` where `access_token` is the PAT plaintext — the dashboard
treats the user as signed in, and every API call ships the PAT as `Authorization:
Bearer …`, which the backend's `SecurityConfig` resolves to the PAT owner's identity.

No Keycloak seed client, no separate test user with a password, no OIDC password
grant. The PAT-owner IS the E2E test user.

### Mint the PAT

Sign in to staging (https://app.arguslog.org) as whoever you want the E2E test user
to be (any regular account — does not need platform-admin). Mint a PAT named
`e2e-runner-permanent` with these scopes:

- `orgs:read`, `orgs:write`
- `projects:read`, `projects:write`
- `keys:read`, `keys:write`
- `releases:read`, `releases:write`
- `issues:read`, `events:read`
- `members:read`, `members:write`
- `alerts:read`, `alerts:write`
- `tokens:read`, `tokens:write`

Copy the plaintext once (it's only shown once).

### GitHub repo secret

Repo Settings → Secrets and variables → Actions → New repository secret:

| Name             | Value                                         |
| ---------------- | --------------------------------------------- |
| `E2E_RUNNER_PAT` | the PAT plaintext from above (`arglog_pat_…`) |

That's the entire prereq. The CI workflow will pick up the secret on the next
deploy-staging → e2e-staging trigger.

### CLI shortcut

If you have `gh` configured for the repo and the PAT plaintext on your clipboard:

```bash
gh secret set E2E_RUNNER_PAT --body 'arglog_pat_…'
```

---

## CI workflow

`.github/workflows/e2e-staging.yml` runs:

- **On every successful staging deploy** (`workflow_run` on `Deploy to staging`).
- **Nightly at 04:00 UTC** (catches drift — expired tokens, Cloudflare config rot, etc.).
- **Manually** via `workflow_dispatch` with an optional `grep` filter.

On failure: HTML report + traces are uploaded as artifacts (`playwright-report/` and
`playwright-traces/`), visible under the run's Actions UI for 7 days.

---

## Cleanup integrity

Every authenticated spec uses the `seededOrg` / `seededProject` / `seededDsn` fixtures
which create unique orgs (`e2e-<runId>-<hash>`) and delete them in teardown. The
cascade deletes everything scoped under the org.

**If a spec is killed mid-teardown** (CI worker timeout, etc.) the orphan org stays
until someone cleans it manually. Quick sweep against staging:

```bash
# Lists e2e-* orgs older than 1h that are stale
curl -sH "Authorization: Bearer $ARGUSLOG_E2E_RUNNER_PAT" \
  https://arguslog.org/api/v1/orgs \
  | jq -r '.[] | select(.slug | startswith("e2e-")) | select(.createdAt < (now - 3600 | strftime("%Y-%m-%dT%H:%M:%SZ"))) | .id'

# Delete them
for id in $(...command above...); do
  curl -X DELETE -H "Authorization: Bearer $ARGUSLOG_E2E_RUNNER_PAT" \
    "https://arguslog.org/api/v1/orgs/${id}"
done
```

A scheduled `cleanup-orphans` workflow is a future enhancement — for now manual sweep
when needed (CI's `cancel-in-progress: false` makes overlapping runs rare).

---

## Adding a new spec

1. Pick the right directory: `tests/landing/` for the public site, `tests/dashboard/`
   for authenticated routes, `tests/auth/` for sign-in flow tests.
2. Import from `../../fixtures/index.js` for authenticated specs (gives you
   `authedPage` + `seededOrg|Project|Dsn` fixtures with auto-cleanup).
3. Use Page Objects from `../../pages/` rather than raw selectors — keeps the suite
   robust to copy / DOM tweaks.
4. Happy paths only. No error / validation coverage in this suite.
5. Run `pnpm typecheck` + `pnpm lint` before committing.

---

## Why PAT-as-OIDC-token (and not real Keycloak login)?

- The dashboard's API client (`apps/web/src/api/client.ts`) just reads
  `useAuthStore.accessToken` and sends it as `Authorization: Bearer …`.
- The backend's `SecurityConfig` has a PAT-aware bearer resolver that handles
  `Bearer arglog_pat_*` strings as Personal Access Tokens, alongside regular
  OIDC JWTs. So a PAT in the access_token slot Just Works.
- `oidc-client-ts`'s `WebStorageStateStore` only reads the user blob — it
  doesn't re-validate the access_token signature on every load. As long as
  `expires_at` is in the future, the user is "valid".
- Therefore: seed an OIDC user blob with `access_token = <PAT>` +
  `expires_at = far_future`, and the dashboard treats the user as signed in
  with no Keycloak roundtrip. The PAT is the E2E test user's identity for
  every request the dashboard makes.

Net effect: a single PAT secret replaces what would otherwise be a Keycloak
seed client + a test user + an OIDC password-grant flow + token-refresh
plumbing.

---

## What this suite deliberately doesn't cover

- **Cross-browser** — Chromium only. Firefox/WebKit add ~15min of CI; the
  user-visible Mantine + React rendering doesn't differ enough to justify it yet.
- **Mobile viewports** — desktop-first product, no mobile coverage.
- **Visual regression / pixel diffing** — out of scope.
- **Performance budgets** — Lighthouse / web-vitals belongs in a separate workflow.
- **Negative paths** — validation errors, permission denials, network failures.
  Covered by unit tests.
- **Browser-extension flows** — see `apps/browser-extension/playwright.config.ts`
  for the sidepanel extension's own E2E suite.
- **Production** — staging is the only target. Prod gets observability.
