# 📘 Arguslog For Dummies

*Книжката, която обяснява как работи целият Arguslog като на 6-годишно дете.*

---

## Съдържание

1. [Какво е Arguslog?](#1-какво-е-arguslog)
2. [Героите в града](#2-героите-в-града)
3. [Йерархията: Орг → Проект → DSN](#3-йерархията-орг--проект--dsn)
4. [Пътуването на една грешка](#4-пътуването-на-една-грешка)
5. [Защо толкова много стаи?](#5-защо-толкова-много-стаи)
6. [Кой пази какво?](#6-кой-пази-какво)
7. [Охраната: Keycloak дълбоко](#7-охраната-keycloak-дълбоко)
8. [Библиотеката отвътре](#8-библиотеката-отвътре)
9. [Полицията: Rate Limits & Quotas](#9-полицията-rate-limits--quotas)
10. [Релизи и Source Maps — магията на превода](#10-релизи-и-source-maps--магията-на-превода)
11. [Къде живее всичко: Deployment](#11-къде-живее-всичко-deployment)
12. [Тестове: четирите нива](#12-тестове-четирите-нива)
13. [MCP — мостът за AI приятели](#13-mcp--мостът-за-ai-приятели)
14. [Публичното огледало](#14-публичното-огледало)
15. [Tiers — новият OSS свят](#15-tiers--новият-oss-свят)
16. [Monorepo магията](#16-monorepo-магията)
17. [Речник](#17-речник)
18. [Финал](#18-финал)

---

## 1. Какво е Arguslog?

Представи си, че имаш игра на телефона — Angry Birds, Minecraft, каквото и да е. Понякога играта се чупи: пада, замръзва, прави нещо странно. Това се казва **бъг** или **грешка**.

Проблемът е: ако ти играеш и играта се счупи в твоя телефон — **програмистът, който я е направил, не знае!** Той не може да я поправи, защото не я вижда.

**Arguslog** е голям град, който помага на програмистите да виждат **всички** счупвания от **всички** играчи по света — на едно място. Като големи очи навсякъде. 👀

Arguslog е **open-source**, **multi-tenant** error tracking платформа — като Sentry, но твоя. Можеш да си я качиш на собствена инфраструктура (self-host), или да ползваш хостната версия на `arguslog.org` безплатно.

**Какво прави конкретно:**
- Хваща нехванати exception-и, log записи и breadcrumbs от JS, JVM и Python кодове чрез first-class SDK-ове
- Прави fingerprint на event-ите, групира ги в issues, пази ги в Postgres+TimescaleDB
- Показва ги в React dashboard за triage
- Праща real-time алерти към Slack, Telegram, webhook-ове или email
- Превежда обратно minified JS stack traces чрез source maps
- Multi-tenant: orgs / projects / members / роли

---

## 2. Героите в града

### 🧙‍♂️ SDK — малките джуджета в играта

В **всяка** игра/приложение живее едно мъничко джудже. Казва се **SDK** (Software Development Kit). Това джудже:

- Седи тихо вътре в играта
- Гледа постоянно
- Когато види, че нещо се счупи → прави писмо със снимка какво стана
- Пише: "Здравей! Аз съм играта 'Marketing Web'. Грешката е в `checkout.js` на ред 42!"

Имаме **много** видове джуджета, защото игрите са на различни езици:
- 🟨 **JavaScript джудже** (`sdk-browser`) — за уеб игри
- ⚛️ **React джудже** (`sdk-react`) — за React приложения
- 🟢 **Node джудже** (`sdk-node`) — за сървърни приложения
- ☕ **Java джудже** (`java-sdk`) — за Spring Boot
- 🐍 **Python джудже** (`python-sdk`) — за Django/FastAPI
- 📱 **React Native джудже** — за телефонни приложения
- 🛍️ **Web3 джудже** — за блокчейн игри
- Vue, Angular, Next.js джуджета също!

Всички говорят **един и същ език** когато пращат писма — затова Arguslog ги разбира всичките.

### 📮 Ingest — пощальонът

Когато джуджето пусне писмо, то лети до **Ingest** — пощальона на града. Ingest стои на голяма врата и:

1. **Проверява паролата** 🔑 — всяко писмо има DSN ключ. Грешен → "Хайде, разкарай се!"
2. **Проверява размера** — макс 200KB
3. **Проверява спам** (rate limit) — ако едно DSN праща 1000 писма в секунда, дроп-ва
4. Казва "Получих го! ✅" и слага писмото на **транспортна лента**

**Защо толкова бързо?** Защото при пик може да има милиони писма за секунди. Ingest трябва да приема за милисекунди — никакво тежко мислене.

### 🎢 Redis Streams — голямата транспортна лента

Знаеш ли как на летище куфарите вървят по лента? Ето точно това е **Redis Stream** `events:incoming`.

- Ingest хвърля писмата на лентата
- Лентата ги държи безопасно
- Ако Worker-ите са по-бавни → лентата е буфер
- Ако Worker-ите заспят → писмата не се губят

**Магията на лентата:** можеш да имаш **много** работници — всички вземат от една лента! Това е durable, на disk, не in-memory.

### 👷 Worker — работягата

В дъното на лентата стои **Worker**. Прави най-много работа:

1. **Чете писмото** от лентата
2. **Прави "пръстов отпечатък"** (fingerprint) — ако 1000 играчи имат същата грешка, Worker казва "това е същият бъг!" и прави 1 запис с брояч 1000
3. **Symbolication** — превежда minified JS код обратно на читаем чрез source maps
4. **Записва в Postgres** — issue + event
5. **Решава да звъни ли** на телефона (Slack/Telegram/email)

### 📚 Postgres + TimescaleDB — библиотеката

Голяма стая, където **всичко** се пази:
- Хора, organizations, projects (нормални рафтове)
- Events (специален рафт — hypertable, разделен по дни)
- Audit log (също hypertable)

**TimescaleDB не е отделна база!** Той е *extension* в Postgres. Едно процесче, една мрежова връзка.

### 🛎️ API — библиотекарят

Когато програмистът иска нещо, не влиза сам в библиотеката — пита **API** (REST):
- "Покажи ми последните 10 грешки!" → API чете от Postgres → връща JSON
- "Преименувай Org3!" → API проверява права, променя

### 🖥️ Web — красивата стая за четене

Сайтът, който отваряш — `arguslog.org`. React + Mantine + Vite. Тук виждаш:
- 📊 Списък с грешки
- 📈 Графики
- 🎯 Подробности
- 👥 Управление на хората

Web няма свой ум — само пита API.

### 🛡️ Keycloak — охранителят на вратата

Преди да влезеш в Web стаята, **Keycloak** охранителят:
- Пита кой си → email + парола
- Дава **bаdge** (JWT token) — носиш го навсякъде
- Пази паролите в свой склад (отделна Postgres база)

### 🗄️ S3/MinIO — складът

За големи неща (sourcemaps):
- В production — **R2** (Cloudflare)
- На локала — **MinIO**

### 🤖 MCP — мост за AI помощници

Специален сървър, по който Claude и други AI агенти говорят с Arguslog. Точно затова мога да викам `list_my_orgs`, `rename_org` от името на потребителя.

---

## 3. Йерархията: Орг → Проект → DSN

Представи си **училище**:

```
🏫 Organization "Arguslog" (училище)
├── 📚 Project "API" (стая 1)
│   └── 🔑 DSN PENT...DZYS (ключ за стая 1)
├── 📚 Project "Web" (стая 2)
│   └── 🔑 DSN MFAQ...BGW3 (ключ за стая 2)
└── 📚 Project "Worker" (стая 3)
    └── 🔑 DSN ABCD...XYZ (ключ за стая 3)
```

- **Един човек** може да е в **много училища** (orgs)
- **Едно училище** има **много стаи** (projects)
- **Всяка стая** има **DSN ключ** за нейните джуджета
- **Хората** имат роли: owner (директор), admin (учител), member (ученик)

---

## 4. Пътуването на една грешка

Хайде да проследим **една грешка** от началото до края:

```
👤 Играч в Бостън отваря Marketing Web в браузъра си
   ↓
💥 БУМ! Бутонът "Купи" се счупи (JavaScript exception)
   ↓
🧙‍♂️ SDK джудже (sdk-browser) вижда грешката, прави писмо:
   "Cannot read property 'price' of undefined
    checkout.js:42, DSN: arguslog://MFAQ...@ingest.arguslog.org/api/17"
   ↓
🌐 Писмото лети през интернет към ingest.arguslog.org
   ↓
📮 Ingest пощальон:
   ✓ Проверява DSN — ОК
   ✓ Не е твърде голямо — ОК
   ✓ Не е спам — ОК
   → Хвърля на Redis лентата
   → Отговаря 202 Accepted (1ms)
   ↓
🎢 Лента "events:incoming" носи писмото
   ↓
👷 Worker взема писмото:
   ✓ Прави fingerprint
   ✓ Има ли вече issue с този fingerprint? НЕ → нов issue
   ✓ Symbolication: чете sourcemap от MinIO
   ✓ Пише в Postgres (events hypertable, днешния chunk)
   ↓
🔔 Worker гледа alert rules:
   "alert на Telegram ако error rate > 10/min" → ТРИГЕР!
   → Праща на alert лентата
   ↓
📨 Alert worker праща в Telegram: "🚨 12 errors in last min!"
   ↓
📱 Програмистът вижда нотификация
   ↓
💻 Отваря arguslog.org/issues/123
   ↓
🌐 Web → GET /api/v1/issues/123
   ↓
🛡️ Keycloak проверява JWT badge — ОК
   ↓
🛎️ API чете от Postgres → връща JSON
   ↓
🖥️ Web показва: "checkout.js:42 — 47 occurrences"
   ↓
🐛 Програмистът поправя, push-ва, deploy-ва
   ↓
✅ Готово!
```

---

## 5. Защо толкова много стаи?

Можеше да направим **една голяма къща**, която прави всичко. Но има 3 проблема:

### Проблем 1: Различни темпа
- Ingest приема МНОГО бързо (10k/sec)
- Worker мисли по-дълбоко (1k/sec)
- API отговаря бавно (100/sec)

Ако са в **една** къща и API закъса → целият Arguslog пада. Като отделни стаи → API може да е бавно, но ingest продължава да приема.

### Проблем 2: Различен брой работници
- Ingest: нужни 5 копия за пиковете
- Worker: нужно само 2
- API: нужно 3

Отделни стаи = scale-ваш всяка поотделно.

### Проблем 3: Различни умения
- Ingest = специалист по бързо приемане
- Worker = специалист по тежко мислене
- API = специалист по запитвания

Като в болница: спешно отделение (ingest), операционна (worker) и приемна (api). Не искаш един доктор да прави всичко.

---

## 6. Кой пази какво?

| Място | Какво пази | Срок |
|---|---|---|
| 📚 **Postgres** | Хора, organizations, проекти, грешки, конфигурации | Завинаги |
| ⏰ **TimescaleDB** (в Postgres) | Самите event-и | 365 дни (Platinum) |
| 🎢 **Redis Streams** | Писма в полет | Минути |
| 🗄️ **S3/MinIO** | Sourcemaps | Колкото държиш |
| 🛡️ **Keycloak Postgres** | Пароли + потребители (отделна база!) | Завинаги |

---

## 7. Охраната: Keycloak дълбоко

### Стъпка-по-стъпка влизане

```
1. 👤 Петар отваря arguslog.org
2. 🌐 Web: "Имаш ли valid badge?" Не!
3. 🔀 Препраща към Keycloak login страница
4. 👤 Петар пише email + парола
5. 🛡️ Keycloak проверява
6. 🎫 Дава ДВА badge-а:
   - access_token (5 минути)
   - refresh_token (дни — за обновяване)
7. 🔀 Препраща обратно към Web
8. 🌐 Web пази badges в браузъра
9. 🛎️ Web вика API + access_token
10. 🛎️ API проверява JWT signature с публичен ключ на Keycloak
11. 🛎️ Изпълнява заявката
```

### Магията на PKCE (за деца)

PKCE = **Proof Key for Code Exchange**. Звучи страшно, ама е просто:

1. Генерираш **тайно число** (verifier) — пазиш го у дома
2. Правиш **печат** от него (challenge = SHA256(verifier))
3. Пращаш печата на Keycloak
4. Keycloak ти праща badge в писмо
5. За да го отвориш, казваш истинското число
6. Keycloak проверява: SHA256(числото) == печата? ✅

Така **никой по пътя не може да открадне баджа** — дори да открадне писмото, няма числото от вкъщи.

---

## 8. Библиотеката отвътре

### Картата на рафтовете

```
📚 БИБЛИОТЕКА "arguslog"
│
├── 📇 ХОРА
│   ├── users
│   └── personal_access_tokens
│
├── 🏫 КОМПАНИИ И СТАИ
│   ├── organizations
│   ├── org_members
│   ├── projects
│   ├── project_members
│   ├── project_keys
│   └── environments
│
├── 📦 РЕЛИЙЗИ И КАРТИ
│   ├── releases
│   └── source_map_artifacts
│
├── 💥 ГРЕШКИ
│   ├── issues
│   └── events ⏰ (HYPERTABLE)
│
├── 🔔 АЛЕРТИ
│   ├── alert_destinations
│   └── alert_rules
│
└── 📜 АУДИТ
    └── audit_log ⏰ (HYPERTABLE)
```

### Магията на Row-Level Security (RLS)

В библиотеката има общ рафт `issues` (всички грешки от всички компании). Но Петар трябва да види САМО грешките на Arguslog org, не на Geo-mini.

**Старият начин (грешен):**
```sql
SELECT * FROM issues WHERE org_id = ? AND ...
```
Един програмист забравя `WHERE org_id` → виждаш чужди грешки! 🚨

**RLS магията:**
```sql
CREATE POLICY tenant_isolation ON issues
  USING (org_id = current_setting('app.org_id')::bigint);
```

В API service:
```java
SET app.org_id = '1';  -- "сега работим за Arguslog"
SELECT * FROM issues;  -- автоматично филтрирано само за org_id=1
```

Без значение колко грешки правят програмистите — **никога няма да изтекат данни между компании**. ⚓

### Continuous Aggregates — магически графики

Dashboard-а показва **графики**: "колко грешки на 5 минути за 24 часа". С милиони events → секунди-минути query.

**TimescaleDB решение:**
```sql
CREATE MATERIALIZED VIEW issue_counts_5m
WITH (timescaledb.continuous) AS
SELECT
  time_bucket('5 minutes', received_at) AS bucket,
  issue_id, count(*)
FROM events GROUP BY 1, 2;
```

Това е **готова рецепта**. Timescale пресмята само нови events, старите buckets вече са пресметнати. Query → милисекунди. ⚡

### Защо TimescaleDB не е отделна база

TimescaleDB е **extension** върху Postgres (като PostGIS). Едно процесче, една network connection. Docker image `timescale/timescaledb:latest-pg16` = Postgres 16 + Timescale shared libraries.

В `V1__initial_schema.sql`:
```sql
CREATE EXTENSION IF NOT EXISTS timescaledb;
SELECT create_hypertable('events', 'received_at', chunk_time_interval => INTERVAL '1 day');
```

`events` изглежда нормално отвън, но вътрешно е разделена на **chunks** по дни. Изтриването на стари events не е `DELETE` (бавно), а **drop chunk** — атомично, O(1).

---

## 9. Полицията: Rate Limits & Quotas

### Ниво 1: Burst Limiter (в Ingest) 🏃
```
Ако едно DSN праща > 100 events/sec → блокирай!
```
**In-memory** (Bucket4j). Бързо, но per-pod. `bucket4j-redis` е P5 followup за cross-instance.

### Ниво 2: Sustained Rate Limit (с Redis) 🚦
По-дълъг прозорец, споделен между pod-овете.

### Ниво 3: Monthly Quota (Tier-based) 📅

| Tier | Events/месец | Projects | Members | Retention |
|---|---|---|---|---|
| 🥉 regular | 5,000 | 1 | 1 | 7 дни |
| 🥈 silver | 50,000 | 5 | 5 | 30 дни |
| 🥇 gold | 500,000 | 20 | 20 | 90 дни |
| 💎 platinum | ∞ | ∞ | ∞ | 365 дни |

Worker проверява всеки event: "коя org? Над лимита?" → ако да → drop.

---

## 10. Релизи и Source Maps — магията на превода

### Защо?

Когато JS се компилира за production:
```js
// Беше:
function calculateTotal(items) {
  return items.reduce((sum, item) => sum + item.price, 0);
}

// Стана:
function a(b){return b.reduce((c,d)=>c+d.e,0)}
```

Грешка `Error in a() at line 1:42` → нищо не разбираш. 😵

### Решение: Source Map

Build-ът прави **карта** (`bundle.js.map`):
```
Ред 1, колона 42 в bundle.js
   = src/billing.ts:15
   = функция "calculateTotal"
   = променлива "price"
```

### Процесът

```
1. 🛠️ pnpm build → bundle.js + bundle.js.map
2. 💻 argus releases upload-sourcemaps --release v1.1.1
3. ⬆️ CLI качва картите в S3/MinIO
4. 📝 CLI казва на API: "нов release v1.1.1"
5. 🌐 Deploy, потребител прави грешка
6. 🧙‍♂️ SDK праща: "Error in a() at 1:42, release=v1.1.1"
7. 👷 Worker:
   ✓ Има ли sourcemap за v1.1.1? Изтегли от S3
   ✓ Преведи: "a()" → "calculateTotal" в "src/billing.ts:15"
   ✓ Запиши преведеното
8. 🖥️ Web показва: "Error in calculateTotal() at src/billing.ts:15"
```

Програмистът разбира веднага. 🎯

---

## 11. Къде живее всичко: Deployment

### 🏠 Живот 1: Local dev

```bash
make dev
```
Стартира:
- 🐳 Docker compose — Postgres+Timescale, Redis, Keycloak, MinIO, Mailhog
- 🟢 mprocs TUI — 4 панела: ingest (8080), api (8081), worker (8082), web (5173)

### 🏢 Живот 2: Staging (Railway, auto от main)

`dev` → `main` merge → GitHub Actions:
1. Build Docker images
2. Push на Railway
3. Deploy на staging environment
4. URL: `staging.arguslog.org`

### 🌍 Живот 3: Production (Railway, manual)

Същото, но **ръчно** trigger. Никакъв auto-deploy в prod!
URL: `arguslog.org`

### Cloudflare като фронт

```
Потребител в Япония
    ↓
🌐 Cloudflare (CDN кешове + DDoS защита + DNS)
    ↓
🚂 Railway (твоите services)
```

---

## 12. Тестове: четирите нива

### 1. Unit tests (75% покритие)
- EventFingerprinter групира правилно?
- DsnValidator отхвърля невалидни DSN?
- Rate limiter брои правилно?

Бързи (милисекунди), много на брой, всеки commit.

### 2. Integration tests (40%)
- Controller + истинска Redis (Testcontainers)?
- Repository + истински Postgres?

По-бавни (секунди), по-малко.

### 3. Contract tests (Pact)
```
SDK казва: "ще пратя {event_id, message, level}"
   ↓ Pact записва
Ingest казва: "очаквам {event_id, message, level}"
   ↓ Pact сравнява
✓ Match → договорът е спазен
```
Гарантира че SDK ↔ Ingest никога не се разминават.

### 4. E2E tests (Playwright, 10%)
Истински браузър → arguslog.org → login → създава org → праща event → проверка в dashboard.

Най-бавни, но най-реалистични.

---

## 13. MCP — мостът за AI приятели

### Голямата картинка

Представи си **ресторант**:
- 📖 **Готварската книга** = `openapi.json` (всички API endpoints)
- 🤖 **Готвачът** = `generate-tools.mjs` (генератор)
- 📋 **Менютата** = MCP tools (50+)
- 👨‍🍳 **Сервитьорът** = MCP dispatcher (`tools.ts`)
- 🍴 **Кухнята** = API service

### Стъпка 1: Spring Boot → OpenAPI spec

Java анотации:
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

При build, `springdoc-openapi` автоматично прави `openapi.json`:
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

### Стъпка 2: Генератор → MCP tools

`packages/mcp-server/scripts/generate-tools.mjs`:

```js
for (const [path, ops] of Object.entries(spec.paths)) {
  for (const [method, op] of Object.entries(ops)) {
    let name = makeName(tag, opId);
    name = name.replace(/_controller_/g, '_');  // махни Spring boilerplate
    // verb-first: "org_rename" → "rename_org"
    // Smithery/Glama marketplace предпочитат verb-first

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

### Стъпка 3: Curated layer

В `curated-tools.ts` има ~15 ръчно написани tools с LLM-friendly описания:

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

### Стъпка 4: Merge magic

В `tools.ts`:
- Auto-gen tools имат **schemas + annotations** (от OpenAPI, точни)
- Curated tools имат **rich descriptions** (LLM-friendly)
- Merge по `method + path` — без дубликати

```ts
const merged = {
  ...auto,
  ...curated,
  outputSchema: curated.outputSchema ?? auto.outputSchema,
  annotations: curated.annotations ?? auto.annotations,
};
```

Резултат: **54 unique tools**, всички с описания **и** schemas.

### Стъпка 5: Runtime dispatch

Когато Claude вика `rename_org(orgId=8, body={name: "OrgThree"})`:

```ts
async function executeTool(client, name, args) {
  const tool = TOOL_REGISTRY.get('rename_org');
  // tool = { method: 'PATCH', path: '/api/v1/orgs/{orgId}', hasBody: true, ... }

  // 1. Замества path параметрите
  let path = tool.path.replace('{orgId}', '8');

  // 2. Разделя body vs query
  const body = args.body;

  // 3. Извиква API
  return client.request({ method: 'PATCH', path, body });
}
```

`ArguslogClient` прави HTTP заявката:
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

**Един dispatcher изпълнява ВСИЧКИТЕ 54 tools.** Не пишеш handler за всеки.

### Целият жизнен цикъл

```
🧑‍💻 Програмист добавя @PatchMapping в Java
   ↓ git commit
🤖 CI build на API → springdoc gen-ва openapi.json
   ↓ commit
🍳 pnpm run generate → openapi-tools.ts
   ↓ (опционално: curated entry)
📦 pnpm publish → @arguslog/mcp-server@2.1.0
   ↓ npm i -g
🔌 Restart Claude Code → reconnect MCP
   ↓
✅ Claude вика новия tool
```

**Един API endpoint = един MCP tool, БЕЗ ръчно писане на handler-и.**

---

## 14. Публичното огледало

Arguslog има **две** GitHub repo-та:

### Repo 1: `petarnenov/arguslog` (PRIVATE)
- `apps/web`, `apps/landing` — източник
- `services/api`, `ingest`, `worker` — Java backend
- `packages/sdk-*` — SDK source
- `infra/` — deployment configs
- `.github/workflows/` — CI/CD
- Всичко останало

Manor house — целият код. Не публичен (Railway secrets, history, …).

### Repo 2: `petarnenov/arguslog-sdks` (PUBLIC)
- `packages/sdk-browser`, `sdk-react`, … — само SDK-овете
- `packages/mcp-server` — MCP-то
- `cli/` — CLI
- `examples/`

Front gate — само open-source. Прави се автоматично:

```bash
scripts/sync-public-mirror.sh
```

При push на `main` в private repo → CI стартира → sync избрани файлове в public mirror → push. SDK-овете могат да се install-ват от npm/PyPI/Maven, README сочи към GitHub, без да издаваш частния код.

---

## 15. Tiers — новият OSS свят

### Стария план (изоставен)
- Lemon Squeezy (карти)
- NOWPayments (crypto)
- $9.99/мес базов план
- 1/3/6/12 месечни durations

Имаше цял billing flow, Stripe webhooks, всичко.

### Новият план (OSS pivot)
- **Без пари в кода**
- Tier-овете все още в схемата
- Раздават се от admin (env-allowlist `ARGUSLOG_PLATFORM_ADMINS`)
- Self-hosted: `ARGUSLOG_DEFAULT_TIER=platinum` → всички unlimited

### Platform Admin

```
🛡️ env: ARGUSLOG_PLATFORM_ADMINS=petar@example.com
    ↓
🛎️ API зарежда списъка при start
    ↓
👤 Когато Петар логва: API → isPlatformAdmin=true
    ↓
🎯 Може:
    ✓ Да види всички orgs (admin_orgs tool)
    ✓ Да даде bonus events
    ✓ Да revoke-ва права
    ✓ Всяко действие → audit_log запис (append-only!)
```

---

## 16. Monorepo магията

Целият код в **един** repo:

```
argus/
├── apps/        ← React приложения
├── services/    ← Java/Spring приложения
├── packages/    ← Споделени TS пакети
├── java-sdk/    ← Java SDK
├── python-sdk/  ← Python SDK
├── cli/         ← Node CLI
└── e2e/         ← Playwright тестове
```

### Полза 1: Atomic changes
Промениш API → същия PR обновява SDK + Web + MCP + docs. Без 5 PR-а в 5 repo-та.

### Полза 2: Shared tooling
`pnpm install` инсталира всичко. `gradle build` build-ва всички services.

### Полза 3: Cross-package refactor
"Преименувай `dsn_key` → `dsn_public`" → grep across the **whole** monorepo, replace, commit. Всеки от 8 SDK + 3 services + web наведнъж.

### Инструментите

- **Turborepo** = intelligent build orchestrator за JS — знае dependencies, build-ва само changes
- **pnpm workspaces** = symlinks между packages — `sdk-react` import-ва от `sdk-core` директно от source
- **Gradle composite build** = същото за Java services

---

## 17. Речник

| Термин | Какво е |
|---|---|
| **DSN** | Data Source Name — public auth credential за SDK → ingest |
| **PAT** | Personal Access Token — user-level token за API/MCP |
| **Fingerprint** | Hash на грешка — еднакви грешки → същи fingerprint → 1 issue |
| **Issue** | Групирана грешка (1 запис на uniквен бъг) |
| **Event** | Индивидуален случай на грешка |
| **Source Map** | Карта от minified ↔ оригинален код |
| **Release** | Версия на приложението (v1.2.3) |
| **Hypertable** | Timescale magic таблица, разделена по време |
| **RLS** | Row-Level Security — автоматично филтриране в Postgres |
| **OIDC** | OpenID Connect — auth протокол (Keycloak ↔ Web) |
| **PKCE** | Proof Key for Code Exchange — extra security за OAuth |
| **MCP** | Model Context Protocol — мост между Claude и tools |
| **Tenant** | Логическо разделение в multi-tenant система (= org) |
| **Symbolication** | Превод от minified към читаем код |

---

## 18. Финал

### Целият голям пъзел в една картинка

```
                    👤 ПОТРЕБИТЕЛИ
                          │
        ┌─────────────────┼─────────────────┐
        ↓                 ↓                 ↓
   🌐 Браузър        📱 Mobile          🖥️ Сървър
        │                 │                 │
        └─────────────────┼─────────────────┘
                          ↓
                  🌍 Cloudflare CDN
                          ↓
                  ┌───────┴───────┐
                  ↓               ↓
            📮 Ingest        🛎️ API
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

### Какво да помниш

1. **Микросервиси, защото имат различни нужди** (скорост vs мислене vs запитвания)
2. **Redis Streams = shock absorber** между бързо приемане и бавно записване
3. **Postgres+Timescale = един процес, magic за time-series**
4. **RLS = автоматична изолация** между orgs
5. **API-first + OpenAPI = SDK/MCP/CLI се генерират сами**
6. **Monorepo = един commit променя всичко наведнъж**
7. **3 environment-а (local/staging/prod), auto-deploy само dev→main**
8. **OSS pivot — без билинг, само admin-granted tiers**
9. **Public mirror = SDK-овете публични, backend-ът частен**

### TL;DR за 6-годишно дете

**Arguslog е голям град, който помага да хващаме счупвания в игри по целия свят:**

1. Малки джуджета (SDK) живеят във игрите и виждат всичко
2. Пращат писма на пощальон (Ingest)
3. Пощальонът ги слага на лента (Redis)
4. Работяга (Worker) ги сортира и записва в библиотека (Postgres)
5. Ако нещо е лошо → звъни на телефона (Slack/Telegram)
6. Програмистът отива в красива стая (Web), пита библиотекаря (API)
7. Вижда грешката, поправя я
8. ВСИЧКО Е ХУБАВО! 🎉

И всичко работи в **един град** (Railway cloud) или в **къщата ти** (self-hosted с Docker).

---

*Край на книжката. Сега вече знаеш как работи Arguslog!* 📚✨
