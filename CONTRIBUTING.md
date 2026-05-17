# Contributing to Arguslog

Thanks for your interest. This file covers the workflow, the gates a PR
has to pass, and the conventions to make review fast.

## Quick start

```bash
git clone https://github.com/petarnenov/arguslog.git
cd arguslog
make doctor                   # verify prereqs; prints OS-specific install commands for any miss
make                          # full stack + watch loops (alias for `make dev`)
make seed                     # optional: demo user + org + sample events (in a second terminal)
# or, for a completely fresh start with demo data auto-loaded:
make demo                     # = reset + fresh + make + seed (seed runs in background, log: /tmp/arguslog-seed.log)
```

See [README.md](README.md) for the make-targets table and individual
service runbooks.

## Branch model

- `dev` is the integration branch. Open PRs against `dev`.
- `main` is staging — the maintainer fast-forward-merges `dev` into `main`
  after green CI, which triggers the staging deploy.
- Production is manual (no auto-deploy from `main`).

## Quality gates

CI runs on every PR. Locally:

```bash
pnpm typecheck    # tsc in every TS workspace
pnpm lint         # ESLint
pnpm test         # vitest, every workspace
./gradlew check   # Java unit + Testcontainers integration
```

Coverage thresholds are configured per-module and are not lowered to make
CI green — write tests instead. The Java side uses Testcontainers
(Postgres+Timescale), so the first `./gradlew check` after a fresh clone
will pull the image (~few minutes); subsequent runs reuse the cached image.

## Coding conventions

- **Comments**: default to none. Only add one when the _why_ is non-obvious
  (a hidden constraint, a workaround for a specific bug, an invariant that
  isn't enforced by types). Don't explain _what_ — well-named identifiers
  do that. Don't reference the current task or callers.
- **Tests**: integration tests run against real Postgres via Testcontainers
  (`@Testcontainers`). Unit tests use Mockito for the application layer.
  Mirror existing patterns in the same module before inventing a new shape.
- **Migrations**: live in `services/api/src/main/resources/db/migration/`
  as `V<N>__<description>.sql`. Flyway is forward-only — there are no
  down migrations. Test destructive changes against a staging clone of
  prod before merging.
- **Frontend**: Mantine v7 is the component library. TanStack Query for
  server state, no global stores. Pages live in `apps/web/src/pages/`,
  per-route API helpers in `apps/web/src/api/`.
- **Java packages**: `org.arguslog.<service>.<layer>` — `application` for
  use cases + ports, `adapter.in.web` for controllers, `adapter.out.postgres`
  for JDBC, `domain` for value objects.

## Tier / billing model

There is no payment code in this repository. Tier elevation
(silver / gold / platinum) is admin-grant only. If your PR introduces a
new feature gated on tier, surface the cap as a method on
`org.arguslog.billing.PlanTier` and read it via `TierLookupRepository` —
don't hardcode tier names in business logic.

## Commit message style

Conventional Commits prefix (`feat:`, `fix:`, `chore:`, `docs:`,
`refactor:`, `test:`) followed by a 1-line summary. Body is optional but
when present should explain _why_, not _what_ — the diff already shows
_what_.

```
fix(worker-billing): keep stream pollers alive on transient connection drops

The default cancelSubscriptionOnError = t -> true silently cancelled the
poller on the first Lettuce "Connection closed" — which is a routine TCP
lifecycle event in Railway's network, not a real bug. This change keeps the
poller alive and lets Lettuce auto-reconnect on the next iteration.
```

## Pull-request checklist

- [ ] CI green (lint, typecheck, test, e2e if relevant)
- [ ] New behavior covered by tests (Vitest / JUnit / Playwright as appropriate)
- [ ] No new env vars without a default + a mention in SELF_HOSTING.md
- [ ] No new external dependencies without a clear justification in the PR
      description
- [ ] If the change touches a public API (REST endpoints, SDK shape, MCP
      tools), regenerate `services/api/openapi.json` via
      `./gradlew :services:api:test --tests OpenApiContractTest`

## Security

Don't open public GitHub issues for security bugs. See [SECURITY.md](SECURITY.md)
for the disclosure process.

## Code of conduct

We follow the Contributor Covenant — see [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).
Be excellent to each other.
