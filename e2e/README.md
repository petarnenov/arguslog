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

You need three secrets — minted once and put in your shell or `.env.local` at repo root:

```bash
export ARGUSLOG_E2E_TEST_USER_EMAIL=e2e@arguslog.local
export ARGUSLOG_E2E_TEST_USER_PASSWORD=<password>
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

# Terminal 2: set local env + run
export ARGUSLOG_E2E_TEST_USER_EMAIL=demo@arguslog.local
export ARGUSLOG_E2E_TEST_USER_PASSWORD=demo
export ARGUSLOG_E2E_RUNNER_PAT=<mint via /me/tokens UI after demo seeds>
cd e2e
pnpm test:local
```

---

## One-time staging-side setup

These must exist before any E2E run against staging. Cardholder of the staging Keycloak
admin needs to do this once.

### 1. Seed client in Keycloak

The dashboard normally authenticates via the `arguslog-web` Keycloak client which has
**Direct Access Grants disabled** (correct production posture). For programmatic E2E
login we need a separate dev-only client:

- Client ID: `arguslog-seed`
- Access Type: `public`
- Direct Access Grants: **enabled**
- Standard Flow: disabled
- Audience mapper: include `arguslog-web` (so the access token's `aud` claim covers
  the dashboard's API audience)

The local realm template (`infra/keycloak/realm.template.json`) already ships this
client — staging should mirror that. If the import was applied before the seed
client was added, re-apply or hand-create through the KC admin UI.

### 2. Dedicated E2E user

- Username/email: `e2e@arguslog.local` (override via `ARGUSLOG_E2E_TEST_USER_EMAIL`)
- Password: generate a strong one; put it in GitHub Secrets as `E2E_TEST_USER_PASSWORD`
- Email verified: yes
- Realm role: regular `user` (not `platform-admin` — the admin spec gracefully detects
  either case)

### 3. Long-lived runner PAT

Sign in as the E2E user on staging, mint a PAT named `e2e-runner-permanent` with
these scopes:

- `orgs:read`, `orgs:write`
- `projects:read`, `projects:write`
- `keys:read`, `keys:write`
- `releases:read`, `releases:write`
- `issues:read`, `events:read`
- `members:read`, `members:write`
- `alerts:read`, `alerts:write`
- `tokens:read`, `tokens:write`

Copy the plaintext once — put it in GitHub Secrets as `E2E_RUNNER_PAT`.

### 4. GitHub repo secrets

Repo Settings → Secrets and variables → Actions → New repository secret. Add three:

| Name                     | Value                                             |
| ------------------------ | ------------------------------------------------- |
| `E2E_TEST_USER_EMAIL`    | `e2e@arguslog.local` (or whatever email you used) |
| `E2E_TEST_USER_PASSWORD` | the password from step 2                          |
| `E2E_RUNNER_PAT`         | the PAT plaintext from step 3                     |

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

## Why programmatic OIDC + a separate runner PAT?

- The Playwright runner exchanges email+password for an OIDC access token via the
  `arguslog-seed` Keycloak client (Direct Access Grants flow). That access token
  is seeded into `localStorage` before the dashboard boots, so the SPA thinks the
  user is signed in and never bounces through the KC redirect.
- The runner PAT (a separate credential) is used for setup/teardown API calls
  (create org, delete org, etc.) from Node-side fixtures. We don't reuse the
  short-lived OIDC access token for setup because each test would need to re-mint
  it; the PAT is long-lived and convenient.
- Both credentials authenticate the same identity (the E2E user). The PAT inherits
  the user's permissions; the OIDC token does the same. No backend test-mode flag
  needed — staging itself is the isolation.

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
