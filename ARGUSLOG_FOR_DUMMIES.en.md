# 📘 Arguslog For Dummies

*The little book that explains how the whole Arguslog works as if to a 6-year-old.*

---

## Contents

1. [What is Arguslog?](#1-what-is-arguslog)
2. [The Characters in Town](#2-the-characters-in-town)
3. [The Hierarchy: Org → Project → DSN](#3-the-hierarchy-org--project--dsn)
4. [The Journey of a Single Error](#4-the-journey-of-a-single-error)
5. [Why So Many Rooms?](#5-why-so-many-rooms)
6. [Who Stores What?](#6-who-stores-what)
7. [The Guard: Keycloak Deep-Dive](#7-the-guard-keycloak-deep-dive)
8. [Inside the Library](#8-inside-the-library)
9. [The Police: Rate Limits & Quotas](#9-the-police-rate-limits--quotas)
10. [Releases and Source Maps — The Translation Magic](#10-releases-and-source-maps--the-translation-magic)
11. [Where Everything Lives: Deployment](#11-where-everything-lives-deployment)
12. [Tests: The Four Tiers](#12-tests-the-four-tiers)
13. [MCP — The Bridge for AI Friends](#13-mcp--the-bridge-for-ai-friends)
14. [The Public Mirror](#14-the-public-mirror)
15. [Tiers — The New OSS World](#15-tiers--the-new-oss-world)
16. [Monorepo Magic](#16-monorepo-magic)
17. [Glossary](#17-glossary)
18. [The End](#18-the-end)

---

## 1. What is Arguslog?

Imagine you have a game on your phone — Angry Birds, Minecraft, whatever. Sometimes the game breaks: it crashes, freezes, does something weird. That's called a **bug** or an **error**.

The problem is: if you're playing and the game breaks on **your** phone — **the programmer who built it doesn't know!** They can't fix it because they can't see it.

**Arguslog** is a big city that helps programmers see **all** the breakages from **all** the players around the world — in one place. Like big eyes everywhere. 👀

Arguslog is an **open-source**, **multi-tenant** error-tracking platform — like Sentry, but yours. You can host it on your own infrastructure (self-host), or use the hosted version at `arguslog.org` for free.

**What it does concretely:**
- Catches uncaught exceptions, log records, and breadcrumbs from JS, JVM and Python code via first-class SDKs
- Fingerprints events, groups them into issues, stores them in Postgres + TimescaleDB
- Shows them in a React dashboard for triage
- Sends real-time alerts to Slack, Telegram, webhooks, or email
- **Triage from Slack** — `/arguslog issues|issue <id>|resolve <id>|release <ver>|
  set-project <slug>` slash commands from any channel; resolutions broadcast
  in-channel so the team sees them without a separate ping
- Translates minified JS stack traces back through source maps
- Multi-tenant: orgs / projects / members / roles

---

## 2. The Characters in Town

### 🧙‍♂️ SDK — the little helpers inside the game

Inside **every** game/app lives one tiny little helper. It's called the **SDK** (Software Development Kit). This helper:

- Sits quietly inside the game
- Watches constantly
- When it sees something break → it writes a letter with a snapshot of what happened
- It writes: "Hello! I'm the game 'Marketing Web'. The error is in `checkout.js` on line 42!"

We have **many** kinds of helpers, because games are written in different languages:
- 🟨 **JavaScript helper** (`sdk-browser`) — for web games
- ⚛️ **React helper** (`sdk-react`) — for React apps
- 🟢 **Node helper** (`sdk-node`) — for server apps
- ☕ **Java helper** (`java-sdk`) — for Spring Boot
- 🐍 **Python helper** (`python-sdk`) — for Django/FastAPI
- 📱 **React Native helper** — for phone apps
- 🛍️ **Web3 helper** — for blockchain games
- Vue, Angular, Next.js helpers as well!

They all speak **the same language** when they send letters — that's why Arguslog understands every one of them.

### 📮 Ingest — the postman

When a helper drops a letter, it flies to **Ingest** — the city's postman. Ingest stands at a big door and:

1. **Checks the password** 🔑 — every letter has a DSN key. Wrong → "Off you go!"
2. **Checks the size** — max 200KB
3. **Checks for spam** (rate limit) — if one DSN sends 1000 letters per second, it drops
4. Says "Got it! ✅" and puts the letter on a **conveyor belt**

**Why so fast?** Because at peak time there can be millions of letters per second. Ingest must accept in milliseconds — no heavy thinking allowed.

### 🎢 Redis Streams — the big conveyor belt

You know how at the airport luggage moves on a belt? That's exactly what **Redis Stream** `events:incoming` is.

- Ingest tosses the letters onto the belt
- The belt holds them safely
- If the Workers are slower → the belt is a buffer
- If the Workers fall asleep → letters aren't lost

**The magic of the belt:** you can have **many** workers — all picking from one belt! It's durable, on disk, not in-memory.

### 👷 Worker — the workhorse

At the far end of the belt stands **Worker**. It does the most work:

1. **Reads the letter** from the belt
2. **Makes a "fingerprint"** — if 1000 players have the same error, Worker says "this is the same bug!" and creates 1 record with a counter of 1000
3. **Symbolication** — translates minified JS code back to readable via source maps
4. **Writes to Postgres** — issue + event
5. **Decides whether to ring** the phone (Slack/Telegram/email)

### 📚 Postgres + TimescaleDB — the library

A big room where **everything** is kept:
- People, organizations, projects (normal shelves)
- Events (a special shelf — hypertable, partitioned by day)
- Audit log (also a hypertable)

**TimescaleDB isn't a separate database!** It's an *extension* inside Postgres. One process, one network connection.

### 🛎️ API — the librarian

When the programmer wants something, they don't walk into the library themselves — they ask **API** (REST):
- "Show me the last 10 errors!" → API reads from Postgres → returns JSON
- "Rename Org3!" → API checks permissions, applies the change

### 🖥️ Web — the pretty reading room

The site you open — `arguslog.org`. React + Mantine + Vite. Here you see:
- 📊 The list of errors
- 📈 Charts
- 🎯 Details
- 👥 People management

Web has no mind of its own — it just asks the API.

### 🛡️ Keycloak — the bouncer at the door

Before you enter the Web room, **Keycloak**, the bouncer:
- Asks who you are → email + password
- Hands you a **badge** (JWT token) — you carry it everywhere
- Keeps passwords in its own pantry (a separate Postgres database)

### 🗄️ S3/MinIO — the warehouse

For big things (sourcemaps):
- In production — **R2** (Cloudflare)
- Locally — **MinIO**

### 🤖 MCP — the bridge for AI helpers

A special server through which Claude and other AI agents talk to Arguslog. That's exactly why I can invoke `list_my_orgs`, `rename_org` on the user's behalf.

---

## 3. The Hierarchy: Org → Project → DSN

Picture a **school**:

```
🏫 Organization "Arguslog" (the school)
├── 📚 Project "API" (classroom 1)
│   └── 🔑 DSN PENT...DZYS (key for classroom 1)
├── 📚 Project "Web" (classroom 2)
│   └── 🔑 DSN MFAQ...BGW3 (key for classroom 2)
└── 📚 Project "Worker" (classroom 3)
    └── 🔑 DSN ABCD...XYZ (key for classroom 3)
```

- **One person** can be in **many schools** (orgs)
- **One school** has **many classrooms** (projects)
- **Every classroom** has a **DSN key** for its little helpers
- **People** carry roles: owner (principal), admin (teacher), member (student)

---

## 4. The Journey of a Single Error

Let's follow **one error** from start to finish:

```
👤 A player in Boston opens Marketing Web in their browser
   ↓
💥 BOOM! The "Buy" button breaks (JavaScript exception)
   ↓
🧙‍♂️ SDK helper (sdk-browser) sees the error, writes a letter:
   "Cannot read property 'price' of undefined
    checkout.js:42, DSN: arguslog://MFAQ...@ingest.arguslog.org/api/17"
   ↓
🌐 The letter flies over the internet to ingest.arguslog.org
   ↓
📮 Ingest the postman:
   ✓ Checks the DSN — OK
   ✓ Not too big — OK
   ✓ Not spam — OK
   → Tosses it on the Redis belt
   → Responds 202 Accepted (1ms)
   ↓
🎢 The "events:incoming" belt carries the letter
   ↓
👷 Worker picks it up:
   ✓ Computes the fingerprint
   ✓ Is there an issue with this fingerprint already? NO → new issue
   ✓ Symbolication: reads the sourcemap from MinIO
   ✓ Writes to Postgres (events hypertable, today's chunk)
   ↓
🔔 Worker scans the alert rules:
   "alert on Telegram if error rate > 10/min" → TRIGGER!
   → Drops a job on the alert stream
   ↓
📨 Alert worker sends to Telegram: "🚨 12 errors in the last min!"
   ↓
📱 The programmer sees the notification
   ↓
💻 Opens arguslog.org/issues/123
   ↓
🌐 Web → GET /api/v1/issues/123
   ↓
🛡️ Keycloak checks the JWT badge — OK
   ↓
🛎️ API reads from Postgres → returns JSON
   ↓
🖥️ Web shows: "checkout.js:42 — 47 occurrences"
   ↓
🐛 Programmer fixes, pushes, deploys
   ↓
✅ Done!
```

---

## 5. Why So Many Rooms?

We could have built **one big house** that does everything. But there are 3 problems with that:

### Problem 1: Different paces

- Ingest accepts FAST (10k/sec)
- Worker thinks deeper (1k/sec)
- API answers slowly (100/sec)

If they're in **one** house and API gets stuck → the whole Arguslog goes down. As separate rooms → API can be slow, but ingest keeps accepting.

### Problem 2: Different worker counts

- Ingest: needs 5 copies for peak load
- Worker: needs only 2
- API: needs 3

Separate rooms = you scale each one independently.

### Problem 3: Different skills

- Ingest = specialist in fast intake
- Worker = specialist in heavy thinking
- API = specialist in queries

Like a hospital: ER (ingest), operating theatre (worker), and reception (api). You don't want one doctor doing everything.

---

## 6. Who Stores What?

| Place | What it keeps | Lifespan |
|---|---|---|
| 📚 **Postgres** | People, organizations, projects, errors, configurations | Forever |
| ⏰ **TimescaleDB** (inside Postgres) | The events themselves | 365 days (Platinum) |
| 🎢 **Redis Streams** | Letters in flight | Minutes |
| 🗄️ **S3/MinIO** | Sourcemaps | As long as you keep them |
| 🛡️ **Keycloak Postgres** | Passwords + users (a separate database!) | Forever |

---

## 7. The Guard: Keycloak Deep-Dive

### Step-by-step sign-in

```
1. 👤 Petar opens arguslog.org
2. 🌐 Web: "Do you have a valid badge?" Nope!
3. 🔀 Redirects to the Keycloak login page
4. 👤 Petar types email + password
5. 🛡️ Keycloak verifies
6. 🎫 Hands out TWO badges:
   - access_token (5 minutes)
   - refresh_token (days — for renewal)
7. 🔀 Redirects back to Web
8. 🌐 Web stores the badges in the browser
9. 🛎️ Web calls API + access_token
10. 🛎️ API verifies JWT signature with Keycloak's public key
11. 🛎️ Executes the request
```

### The magic of PKCE (for kids)

PKCE = **Proof Key for Code Exchange**. Sounds scary, but it's simple:

1. You generate a **secret number** (verifier) — keep it at home
2. You make a **seal** from it (challenge = SHA256(verifier))
3. You send the seal to Keycloak
4. Keycloak mails you a badge in an envelope
5. To open the envelope, you whisper the original number
6. Keycloak checks: SHA256(your number) == the seal? ✅

That way **nobody along the path can steal the badge** — even if they steal the envelope, they don't have the number you kept at home.

---

## 8. Inside the Library

### The map of the shelves

```
📚 LIBRARY "arguslog"
│
├── 📇 PEOPLE
│   ├── users
│   └── personal_access_tokens
│
├── 🏫 COMPANIES AND CLASSROOMS
│   ├── organizations
│   ├── org_members
│   ├── projects
│   ├── project_members
│   ├── project_keys
│   └── environments
│
├── 📦 RELEASES AND MAPS
│   ├── releases
│   └── source_map_artifacts
│
├── 💥 ERRORS
│   ├── issues
│   └── events ⏰ (HYPERTABLE)
│
├── 🔔 ALERTS
│   ├── alert_destinations
│   └── alert_rules
│
└── 📜 AUDIT
    └── audit_log ⏰ (HYPERTABLE)
```

### The magic of Row-Level Security (RLS)

In the library there's a shared shelf `issues` (all errors from all companies). But Petar should see ONLY the errors of the Arguslog org, not of Geo-mini.

**The old (wrong) way:**
```sql
SELECT * FROM issues WHERE org_id = ? AND ...
```
One developer forgets `WHERE org_id` → you see someone else's errors! 🚨

**The RLS magic:**
```sql
CREATE POLICY tenant_isolation ON issues
  USING (org_id = current_setting('app.org_id')::bigint);
```

In the API service:
```java
SET app.org_id = '1';  -- "we're working for Arguslog right now"
SELECT * FROM issues;  -- automatically filtered to org_id=1 only
```

No matter how many mistakes the developers make — **data will never leak across companies**. ⚓

### Continuous Aggregates — magical charts

The dashboard shows **charts**: "errors per 5 minutes over 24 hours". With millions of events → seconds-to-minutes per query.

**TimescaleDB's solution:**
```sql
CREATE MATERIALIZED VIEW issue_counts_5m
WITH (timescaledb.continuous) AS
SELECT
  time_bucket('5 minutes', received_at) AS bucket,
  issue_id, count(*)
FROM events GROUP BY 1, 2;
```

This is a **pre-computed recipe**. Timescale only re-computes new events; the old buckets are already done. Queries → milliseconds. ⚡

### Why TimescaleDB isn't a separate database

TimescaleDB is an **extension** on top of Postgres (like PostGIS). One process, one network connection. The Docker image `timescale/timescaledb:latest-pg16` = Postgres 16 + Timescale shared libraries.

In `V1__initial_schema.sql`:
```sql
CREATE EXTENSION IF NOT EXISTS timescaledb;
SELECT create_hypertable('events', 'received_at', chunk_time_interval => INTERVAL '1 day');
```

`events` looks normal on the outside, but internally it's partitioned into **chunks** by day. Deleting old events isn't `DELETE` (slow) — it's a **drop chunk** — atomic, O(1).

---

## 9. The Police: Rate Limits & Quotas

### Tier 1: Burst Limiter (in Ingest) 🏃
```
If one DSN sends > 100 events/sec → block!
```
**In-memory** (Bucket4j). Fast, but per-pod. `bucket4j-redis` is a P5 follow-up for cross-instance limits.

### Tier 2: Sustained Rate Limit (with Redis) 🚦
A longer window, shared across pods.

### Tier 3: Monthly Quota (Tier-based) 📅

| Tier | Events/month | Projects | Members | Retention |
|---|---|---|---|---|
| 🥉 regular | 5,000 | 1 | 1 | 7 days |
| 🥈 silver | 50,000 | 5 | 5 | 30 days |
| 🥇 gold | 500,000 | 20 | 20 | 90 days |
| 💎 platinum | ∞ | ∞ | ∞ | 365 days |

The Worker checks every event: "which org? Over the limit?" → if yes → drop.

---

## 10. Releases and Source Maps — The Translation Magic

### Why?

When JS is compiled for production:
```js
// It was:
function calculateTotal(items) {
  return items.reduce((sum, item) => sum + item.price, 0);
}

// It became:
function a(b){return b.reduce((c,d)=>c+d.e,0)}
```

An error like `Error in a() at line 1:42` → tells you nothing. 😵

### Solution: Source Map

The build emits a **map** (`bundle.js.map`):
```
Line 1, column 42 in bundle.js
   = src/billing.ts:15
   = function "calculateTotal"
   = variable "price"
```

### The flow

```
1. 🛠️ pnpm build → bundle.js + bundle.js.map
2. 💻 argus releases upload-sourcemaps --release v1.1.1
3. ⬆️ CLI uploads the maps to S3/MinIO
4. 📝 CLI tells the API: "new release v1.1.1"
5. 🌐 Deploy, a user hits an error
6. 🧙‍♂️ SDK sends: "Error in a() at 1:42, release=v1.1.1"
7. 👷 Worker:
   ✓ Is there a sourcemap for v1.1.1? Pull it from S3
   ✓ Translate: "a()" → "calculateTotal" in "src/billing.ts:15"
   ✓ Save the translated form
8. 🖥️ Web displays: "Error in calculateTotal() at src/billing.ts:15"
```

The developer understands instantly. 🎯

---

## 11. Where Everything Lives: Deployment

### 🏠 Life 1: Local dev

```bash
make dev
```
Starts:
- 🐳 Docker compose — Postgres+Timescale, Redis, Keycloak, MinIO, Mailhog
- 🟢 mprocs TUI — 4 panels: ingest (8080), api (8081), worker (8082), web (5173)

### 🏢 Life 2: Staging (Railway, auto on main)

`dev` → `main` merge → GitHub Actions:
1. Builds Docker images
2. Pushes to Railway
3. Deploys to the staging environment
4. URL: `staging.arguslog.org`

### 🌍 Life 3: Production (Railway, manual)

Same flow, but **triggered manually**. No auto-deploy in prod!
URL: `arguslog.org`

### Cloudflare in front

```
A user in Japan
    ↓
🌐 Cloudflare (CDN caches + DDoS protection + DNS)
    ↓
🚂 Railway (your services)
```

---

## 12. Tests: The Four Tiers

### 1. Unit tests (75% coverage)
- Does EventFingerprinter group things correctly?
- Does DsnValidator reject invalid DSNs?
- Does the rate limiter count properly?

Fast (milliseconds), many of them, every commit.

### 2. Integration tests (40%)
- Controller + real Redis (Testcontainers)?
- Repository + real Postgres?

Slower (seconds), fewer of them.

### 3. Contract tests (Pact)
```
SDK says: "I'll send {event_id, message, level}"
   ↓ Pact records it
Ingest says: "I expect {event_id, message, level}"
   ↓ Pact compares
✓ Match → the contract holds
```
Guarantees that SDK ↔ Ingest never drift out of sync.

### 4. E2E tests (Playwright, 10%)
Real browser → arguslog.org → login → creates an org → sends an event → verifies it in the dashboard.

The slowest tier but the most realistic.

---

## 13. MCP — The Bridge for AI Friends

### The big picture

Picture a **restaurant**:
- 📖 **The cookbook** = `openapi.json` (all API endpoints)
- 🤖 **The cook** = `generate-tools.mjs` (the generator)
- 📋 **The menus** = MCP tools (50+)
- 👨‍🍳 **The waiter** = MCP dispatcher (`tools.ts`)
- 🍴 **The kitchen** = the API service

### Step 1: Spring Boot → OpenAPI spec

Java annotations:
```java
@RestController
@RequestMapping("/api/v1/orgs")
@Tag(name = "orgs")
public class OrgController {
  @PatchMapping("/{orgId}")
  @Operation(operationId = "rename", summary = "Rename an org")
  public OrgDto rename(@PathVariable long orgId, @RequestBody RenameRequest body) { ... }
}
```

At build time, `springdoc-openapi` automatically generates `openapi.json`:
```json
{
  "paths": {
    "/api/v1/orgs/{orgId}": {
      "patch": {
        "operationId": "rename",
        "summary": "Rename an org",
        "parameters": [{"name": "orgId", "in": "path", ...}],
        "requestBody": {...},
        "responses": {"200": {...}}
      }
    }
  }
}
```

### Step 2: The generator → MCP tools

`packages/mcp-server/scripts/generate-tools.mjs`:

```js
for (const [path, ops] of Object.entries(spec.paths)) {
  for (const [method, op] of Object.entries(ops)) {
    let name = makeName(tag, opId);
    name = name.replace(/_controller_/g, '_');  // strip Spring boilerplate
    // verb-first: "org_rename" → "rename_org"
    // Smithery/Glama marketplaces prefer verb-first

    const outputSchema = extractOutputSchema(op, spec);
    const annotations = makeAnnotations(method, summary);
    // GET → readOnlyHint: true
    // DELETE → destructiveHint: true
    // PUT/PATCH → idempotentHint: true

    tools.push({ name, method, path, pathParams, queryParams, hasBody, outputSchema, annotations });
  }
}

writeFileSync('src/generated/openapi-tools.ts', ...);
```

### Step 3: Curated layer

`curated-tools.ts` holds ~15 hand-written tools with LLM-friendly descriptions:

```ts
list_my_orgs: {
  name: 'list_my_orgs',
  description: `List the organizations the authenticated user is a member of.

Always start here. Most other tools need an \`orgId\` from this list...

Example: call this tool first to discover the user's orgs, pick the right one,
then pass its \`id\` to other tools.`,
  method: 'GET',
  path: '/api/v1/orgs',
  pathParams: [], queryParams: [], hasBody: false,
}
```

### Step 4: Merge magic

In `tools.ts`:
- Auto-gen tools carry **schemas + annotations** (from OpenAPI, accurate)
- Curated tools carry **rich descriptions** (LLM-friendly)
- They merge by `method + path` — no duplicates

```ts
const merged = {
  ...auto,
  ...curated,
  outputSchema: curated.outputSchema ?? auto.outputSchema,
  annotations: curated.annotations ?? auto.annotations,
};
```

Result: **54 unique tools**, each with both a description **and** schemas.

### Step 5: Runtime dispatch

When Claude calls `rename_org(orgId=8, body={name: "OrgThree"})`:

```ts
async function executeTool(client, name, args) {
  const tool = TOOL_REGISTRY.get('rename_org');
  // tool = { method: 'PATCH', path: '/api/v1/orgs/{orgId}', hasBody: true, ... }

  // 1. Substitute path parameters
  let path = tool.path.replace('{orgId}', '8');

  // 2. Split body vs query
  const body = args.body;

  // 3. Call the API
  return client.request({ method: 'PATCH', path, body });
}
```

`ArguslogClient` makes the HTTP request:
```ts
fetch('https://api.arguslog.org/api/v1/orgs/8', {
  method: 'PATCH',
  headers: {
    'Authorization': `Bearer ${ARGUSLOG_PAT}`,
    'Content-Type': 'application/json',
  },
  body: JSON.stringify({ name: "OrgThree" }),
});
```

**One dispatcher executes ALL 54 tools.** You don't write a handler per tool.

### The full life cycle

```
🧑‍💻 Developer adds @PatchMapping in Java
   ↓ git commit
🤖 CI builds API → springdoc generates openapi.json
   ↓ commit
🍳 pnpm run generate → openapi-tools.ts
   ↓ (optional: curated entry)
📦 pnpm publish → @arguslog/mcp-server@2.1.0
   ↓ npm i -g
🔌 Restart Claude Code → reconnect MCP
   ↓
✅ Claude calls the new tool
```

**One API endpoint = one MCP tool, WITHOUT writing any handlers by hand.**

---

## 14. The Public Mirror

Arguslog has **two** GitHub repos:

### Repo 1: `petarnenov/arguslog` (PRIVATE)
- `apps/web`, `apps/landing` — sources
- `services/api`, `ingest`, `worker` — Java backend
- `packages/sdk-*` — SDK source
- `infra/` — deployment configs
- `.github/workflows/` — CI/CD
- Everything else

The manor house — all the code. Not public (Railway secrets, history, …).

### Repo 2: `petarnenov/arguslog-sdks` (PUBLIC)
- `packages/sdk-browser`, `sdk-react`, … — only the SDKs
- `packages/mcp-server` — the MCP
- `cli/` — the CLI
- `examples/`

The front gate — open-source pieces only. Built automatically:

```bash
scripts/sync-public-mirror.sh
```

On a `main` push in the private repo → CI fires → syncs the selected files into the public mirror → pushes. The SDKs install from npm/PyPI/Maven, the README points to GitHub, without leaking the private code.

---

## 15. Tiers — The New OSS World

### The old plan (abandoned)
- Lemon Squeezy (cards)
- NOWPayments (crypto)
- $9.99/month base plan
- 1/3/6/12-month durations

There was a whole billing flow, Stripe webhooks, the works.

### The new plan (OSS pivot)
- **No money in the code**
- The tier values still live in the schema
- They're granted by an admin (env allow-list `ARGUSLOG_PLATFORM_ADMINS`)
- Self-hosted: `ARGUSLOG_DEFAULT_TIER=platinum` → everyone unlimited

### Platform Admin

```
🛡️ env: ARGUSLOG_PLATFORM_ADMINS=petar@example.com
    ↓
🛎️ API loads the list at start
    ↓
👤 When Petar logs in: API → isPlatformAdmin=true
    ↓
🎯 They can:
    ✓ See every org (admin_orgs tool)
    ✓ Grant bonus events
    ✓ Revoke access
    ✓ Every action → an audit_log row (append-only!)
```

---

## 16. Monorepo Magic

All code in **one** repo:

```
argus/
├── apps/        ← React apps
├── services/    ← Java/Spring services
├── packages/    ← Shared TS packages
├── java-sdk/    ← Java SDK
├── python-sdk/  ← Python SDK
├── cli/         ← Node CLI
└── e2e/         ← Playwright tests
```

### Benefit 1: Atomic changes

Change the API → the same PR updates the SDK + Web + MCP + docs. No five PRs in five repos.

### Benefit 2: Shared tooling

`pnpm install` installs everything. `gradle build` builds every service.

### Benefit 3: Cross-package refactor

"Rename `dsn_key` → `dsn_public`" → grep across the **whole** monorepo, replace, commit. Each of the 8 SDKs + 3 services + web in one shot.

### The tools

- **Turborepo** = an intelligent build orchestrator for JS — knows the dependency graph, builds only what changed
- **pnpm workspaces** = symlinks between packages — `sdk-react` imports `sdk-core` straight from source
- **Gradle composite build** = same idea for Java services

---

## 17. Glossary

| Term | What it is |
|---|---|
| **DSN** | Data Source Name — a public auth credential for SDK → ingest |
| **PAT** | Personal Access Token — a user-level token for API/MCP |
| **Fingerprint** | A hash of an error — identical errors → same fingerprint → 1 issue |
| **Issue** | A grouped error (one row per unique bug) |
| **Event** | An individual occurrence of an error |
| **Source Map** | A map between minified ↔ original code |
| **Release** | A version of the app (v1.2.3) |
| **Hypertable** | A Timescale magic table partitioned by time |
| **RLS** | Row-Level Security — automatic filtering inside Postgres |
| **OIDC** | OpenID Connect — auth protocol (Keycloak ↔ Web) |
| **PKCE** | Proof Key for Code Exchange — extra security for OAuth |
| **MCP** | Model Context Protocol — bridge between Claude and tools |
| **Tenant** | A logical partition in a multi-tenant system (= an org) |
| **Symbolication** | The translation from minified to readable code |

---

## 18. The End

### The whole puzzle in one picture

```
                    👤 USERS
                          │
        ┌─────────────────┼─────────────────┐
        ↓                 ↓                 ↓
   🌐 Browser         📱 Mobile          🖥️ Server
        │                 │                 │
        └─────────────────┼─────────────────┘
                          ↓
                  🌍 Cloudflare CDN
                          ↓
                  ┌───────┴───────┐
                  ↓               ↓
            📮 Ingest         🛎️ API
                  ↓               ↓
            🎢 Redis ←──── 👷 Worker ────→ 📚 Postgres+Timescale
                                ↓
                          🗄️ S3/MinIO
                                ↓
                          🔔 Slack/Telegram/Email

                  🛡️ Keycloak (Auth)
                  🤖 MCP server (Claude bridge)
                  💻 CLI (releases tool)
                  🌐 Web Dashboard
                  🌐 Landing
```

### What to remember

1. **Microservices, because they have different needs** (speed vs thinking vs queries)
2. **Redis Streams = shock absorber** between fast intake and slow writes
3. **Postgres + Timescale = one process, magic for time-series**
4. **RLS = automatic isolation** between orgs
5. **API-first + OpenAPI = SDK/MCP/CLI generate themselves**
6. **Monorepo = one commit changes everything at once**
7. **Three environments (local/staging/prod), auto-deploy only on dev→main**
8. **OSS pivot — no billing, only admin-granted tiers**
9. **Public mirror = SDKs public, backend private**

### TL;DR for a 6-year-old

**Arguslog is a big city that helps catch breakages in games around the whole world:**

1. Tiny little helpers (SDKs) live inside the games and see everything
2. They send letters to a postman (Ingest)
3. The postman puts them on a belt (Redis)
4. A workhorse (Worker) sorts them and writes them into a library (Postgres)
5. If something is bad → it rings a phone (Slack/Telegram)
6. The programmer walks into a pretty room (Web), asks the librarian (API)
7. They spot the error and fix it
8. EVERYTHING'S WELL! 🎉

And it all runs in **one city** (Railway cloud) or in **your house** (self-hosted with Docker).

---

*End of the book. Now you know how Arguslog works!* 📚✨
