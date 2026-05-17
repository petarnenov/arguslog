# Local Hosting — `make` brings up the whole stack on a fresh clone

## Context

The goal: someone clones or forks `arguslog`, types **`make`**, and ends up with the
full stack running locally — API + Worker + Ingest + Web + Postgres + Redis + Keycloak +
MinIO + MailHog — ready to log in and explore. No "now run `pnpm install`, then…" extra
steps.

Good news: most of the machinery already exists. `make dev` (`Makefile`) currently runs
`doctor → install → build-sdks → up → mprocs TUI` and that chain works end-to-end. The
gap to "one-command experience" is small and well-defined:

1. **Typing `make` alone does nothing useful** — there is no default target. A fresh
   cloner has to read README to know it's `make dev`.
2. **`make doctor` reports missing tools but doesn't tell you how to install them** for
   your OS. Newcomers hit a wall on first run.
3. **First login is empty** — no demo org, no demo project, no demo events. The dashboard
   looks broken until the user manually creates everything. No "wow" first run.

User decisions (recorded for the implementer):

- `make` (no args) → **`make dev`** (full stack up in mprocs TUI).
- `make doctor` on missing tool → **fail with the OS-specific install one-liner**, do not
  auto-install (sudo prompts are friction).
- Demo data → **new opt-in `make seed` target**, not part of the default flow.

---

## Existing pieces to reuse (don't reinvent)

Inventory from Phase 1 exploration:

| Piece                    | Where                               | What it does                                                                                          |
| ------------------------ | ----------------------------------- | ----------------------------------------------------------------------------------------------------- |
| `make dev`               | `Makefile`                          | Full orchestration: doctor → install → build-sdks → up → mprocs                                       |
| `make doctor`            | `Makefile` (~line 282)              | Verifies docker, pnpm, mprocs, java (JDK 21), gradlew                                                 |
| `make up`                | `Makefile`                          | `docker compose up --wait` with healthchecks (Postgres / Redis / Keycloak / MinIO / MailHog)          |
| `make install`           | `Makefile`                          | `pnpm install --frozen-lockfile` at workspace root                                                    |
| `make build-sdks`        | `Makefile`                          | Incremental `tsc` build of `@arguslog/sdk-*` packages                                                 |
| Compose stack            | `infra/docker/docker-compose.yml`   | All infra services with healthchecks                                                                  |
| Keycloak realm rendering | `services/keycloak/render-realm.sh` | Renders realm JSON with `DEV_HOST` substitution                                                       |
| `.env.example`           | repo root                           | Sane localhost defaults; every third-party (Stripe, Resend, Slack, R2) gracefully degrades when blank |
| `mprocs.yaml`            | repo root                           | 4-panel TUI auto-running ingest / api / worker / web                                                  |
| CLI `arguslog ping`      | `cli/src/commands/ping.ts`          | Sends a synthetic event through ingest given a project id                                             |

All three of the changes below are **additive** — nothing existing breaks.

---

## Phase 1 — Default target (~2 lines of Makefile)

In `Makefile`, near the top (after the SHELL declaration), add:

```make
.DEFAULT_GOAL := dev
```

That's it. `make` with no args now runs `make dev`.

Belt-and-suspenders: keep a `.PHONY: dev help …` list updated. Verify with
`make -p | grep DEFAULT_GOAL` and `make` from a clean shell.

---

## Phase 2 — `make doctor` with OS-specific install hints (~40 lines)

Today (per Phase 1 inventory, `Makefile:~282-288`) `make doctor` just prints "missing:
mprocs" and exits non-zero. Make it actionable.

Rewrite the doctor target so each missing tool prints the **exact install command** for
the detected OS. Detect with `uname -s` (Darwin / Linux). For Linux, prefer apt if
`apt-get` is on path; fall back to a generic curl-installer hint otherwise.

Required tools + canonical install commands:

| Tool         | macOS                                          | Linux (Debian/Ubuntu)                                                  | Linux (generic)                                                   |
| ------------ | ---------------------------------------------- | ---------------------------------------------------------------------- | ----------------------------------------------------------------- |
| `docker`     | `brew install --cask docker`                   | `sudo apt install docker.io docker-compose-plugin`                     | "Install Docker Engine — https://docs.docker.com/engine/install/" |
| `pnpm`       | `brew install pnpm`                            | `curl -fsSL https://get.pnpm.io/install.sh \| sh -`                    | same                                                              |
| `mprocs`     | `brew install mprocs`                          | `cargo install mprocs` (or download release binary)                    | same                                                              |
| Java 21      | `brew install openjdk@21` + `export PATH` hint | `curl -s https://get.sdkman.io \| bash && sdk install java 21.0.5-tem` | same                                                              |
| `node` (≥22) | `brew install node@22`                         | `nvm install 22 && nvm use 22`                                         | "Install Node 22 — https://nodejs.org/"                           |

Behavior:

- Check **every** tool, collect all misses (don't bail on first failure).
- For each miss, print a `❌ <tool> missing — install with: <command>`.
- After all checks, exit 1 if any missing.
- On all-pass, print `✅ all prerequisites OK` and exit 0.

Java needs version detection (`java -version 2>&1 | grep -q '"21'`), not just presence —
JDK 11/17 are common and won't build this project.

Drop this logic into a shell function inside the Makefile or a helper script at
`scripts/doctor.sh` (called by `make doctor`). The script form is preferred for testability
— add `scripts/doctor.sh --json` mode that emits machine-readable output for CI.

---

## Phase 3 — `make seed` for demo data (~150 lines of bash)

New target: `make seed`. **Opt-in**, not part of the default flow. Run AFTER `make dev`
has the stack up.

Steps the script performs (each idempotent, safe to re-run):

1. **Wait for API readiness**: poll `http://localhost:${API_PORT:-8081}/actuator/health/readiness`
   for up to 60s.
2. **Create a demo Keycloak user** via the admin API:
   - Get admin token: `POST http://localhost:8180/realms/master/protocol/openid-connect/token`
     with `admin / admin` (the local-dev default from compose).
   - `POST /admin/realms/arguslog/users` with `username=demo@arguslog.local`,
     `email=demo@arguslog.local`, `enabled=true`, and a `credentials` block setting
     password `demo` (`temporary=false`).
   - If the user already exists (409), continue silently.
3. **Get a user token** via Direct Access Grant: `POST .../arguslog/protocol/openid-connect/token`
   with `grant_type=password`, `client_id=arguslog-web`, `username=demo@arguslog.local`,
   `password=demo`.
4. **Mint an org via the API**: `POST /api/v1/orgs` with `{"name":"Demo Org"}`. The first
   request from a new user auto-onboards them as owner. Capture the org id from the
   response.
5. **Create a project**: `POST /api/v1/orgs/{orgId}/projects` with
   `{"name":"Demo App","platform":"javascript"}`. The response carries `{project, dsn}` —
   capture both.
6. **Fire 8-12 synthetic events** through ingest using the DSN. Vary level (error / warning
   / info), spread `received_at` across the last 14 days for the sparkline. Reuse the CLI
   `arguslog ping` helper if `ARGUSLOG_TOKEN` can be wired temporarily, or POST directly
   to ingest with the DSN-derived envelope.
7. **Print a banner**:
   ```
   ✅ Demo data ready.
      Dashboard:   http://localhost:5173
      Sign in as:  demo@arguslog.local / demo
      Project:     Demo App  (org slug: demo-org)
   ```

Implementation: `scripts/seed-demo.sh`. Heavy use of `curl` + `jq`. Add `jq` to the doctor
checks since the seed script needs it.

Wire it up:

```make
.PHONY: seed
seed:
	@bash scripts/seed-demo.sh
```

If the script can't reach the API (`/readiness` 404 / connection refused), exit with a
friendly "is `make dev` running in another terminal?" message.

---

## Phase 4 — README quick start (~10 lines)

Replace the `## Quick start` section's bullet list with:

````markdown
## Quick start

Prereqs: Docker, JDK 21, Node 22, pnpm, mprocs. Run `make doctor` to confirm + get
install commands for your OS.

```bash
git clone https://github.com/petarnenov/arguslog.git
cd arguslog
make                      # bring up the whole stack (one command)
# (optional, in a separate terminal:)
make seed                 # mint demo org + project + sample events
```
````

Then open <http://localhost:5173>. Sign up with any email; `make seed` already created
`demo@arguslog.local / demo` if you ran it.

````

Mirror the same update in `CONTRIBUTING.md`.

---

## Critical files

- `Makefile` (add `.DEFAULT_GOAL`, rewire `doctor`, add `seed` target)
- `scripts/doctor.sh` *(new)* — multi-OS prerequisite checker
- `scripts/seed-demo.sh` *(new)* — Keycloak + API + ingest bootstrap
- `README.md` (Quick start section)
- `CONTRIBUTING.md` (Quick start section, if it duplicates README's)
- `mprocs.yaml` *(no change — already correct)*
- `infra/docker/docker-compose.yml` *(no change — already correct)*

## Verification

Run on a **fresh clone** in a clean shell (the real test).

```bash
# Cold clone
git clone https://github.com/petarnenov/arguslog.git fresh-test
cd fresh-test

# Prereqs check should be actionable
make doctor                # if anything's missing, install per printed command

# One-command launch
make                       # = make dev; stack comes up in mprocs TUI

# In a separate terminal
make seed                  # creates demo org + events
open http://localhost:5173 # sign in as demo@arguslog.local / demo
````

Success criteria:

1. `make` with zero args triggers `make dev`.
2. `make doctor` on a machine missing `mprocs` prints `brew install mprocs` (macOS) or
   `cargo install mprocs` (Linux), exits 1.
3. `make doctor` on a machine with Java 17 detects the wrong major version and tells the
   user how to install 21.
4. After `make` finishes booting, mprocs shows 4 healthy panels and
   <http://localhost:5173> loads.
5. After `make seed`, <http://localhost:5173> after sign-in shows the Demo App project
   with a non-empty issue list + sparkline data.
6. `make seed` re-run is a no-op (idempotent) — no duplicate users / projects / extra
   events.
7. CI: add a `self-host-smoke` job (or extend the existing `make self-host-test` target)
   that runs `make` then `curl /actuator/health/readiness` on each service.

## Risks

- **Java version detection on macOS**: `/usr/libexec/java_home -v 21` is more reliable
  than parsing `java -version`. Doctor script should prefer it when present.
- **Keycloak admin-API rate limiting on cold start**: poll readiness before the user
  POST. Already handled in step 1 of the seed.
- **Seed script idempotency**: re-running must not double-create. Check existence before
  every POST (user lookup by username, project lookup by slug).
- **mprocs not on Linux package managers**: fall back to `cargo install mprocs` and
  mention the GitHub releases binary as a manual option.
- **DEV_HOST cross-device flow** (existing memory note): the realm rendering script
  already handles this. Doctor doesn't need to touch it; just note it in README under a
  "Develop from a phone on your LAN" sub-section.

## Out of scope (future passes)

- Windows / WSL support beyond what already works through bash + Docker. Document
  "use WSL2" if needed.
- Native installer scripts (`curl | bash` style) — too risky for a project of this
  surface area.
- Replacing `mprocs` with a built-in process manager. The current TUI is fine.
