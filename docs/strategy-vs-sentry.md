# Arguslog — стратегически анализ срещу Sentry

> Бележка: проектът е **Sentry-like** (error tracking). Stripe в кодбейса е само
> платежен интегратор за билинга (P4/P5), не е product reference.

## Sentry — силни и слаби страни

### Силни

- **Developer-first onboarding** — `Sentry.init(dsn)` и работи. SDK-та за 50+ платформи.
- **Stack trace + source maps + breadcrumbs** — не просто "грешка X", а _защо_ се
  случи: последните 50 действия преди срива.
- **Issue grouping / fingerprinting** — 10 000 експлоужъна на същия NPE = 1 issue,
  не 10 000 имейла.
- **Releases + regression detection** — "тази грешка се появи отново в v2.4.1" е
  killer feature за teams с CI/CD.
- **Performance + profiling + session replay** — един SKU обхваща errors → traces
  → replay.
- **Self-host опция** — open source ядрото държи enterprise-ите спокойни.

### Слаби

- **Цените експлодират** — $26/mo базов план, но при production трафик бързо отива
  на $200–500+/mo. Pay-per-event е жесток.
- **UI е претрупан** — 7 години feature creep; нови users се губят.
- **Quota model е наказващ** — превишаваш cap → drop events → грешките които _най-много_
  искаш да видиш изчезват.
- **Self-host е тежък** — 20+ контейнера, Kafka, ClickHouse. "Open source" но
  практически unlovable.
- **Slow за малки teams** — твърде много фичи за някой който просто иска "кажи ми
  когато prod гръмне".

## Сравнение с Arguslog

| Измерение           | Sentry               | Arguslog                         |
| ------------------- | -------------------- | -------------------------------- |
| SDK breadth         | 50+                  | 2 (browser/react + java)         |
| Performance/Replay  | Да                   | Не                               |
| Цена @ 100k events  | ~$26–80/mo           | $9/mo                            |
| Self-host           | Heavy (20+ services) | 3 services + Postgres/Redis/R2   |
| Multi-tenancy       | Org-based            | Org-based + RLS                  |
| Source maps         | Да                   | Да (CLI)                         |
| Releases/regression | Да                   | Releases да, regression — не yet |
| Issue grouping      | Sophisticated        | Basic (трябва проверка)          |

**Структурно предимство:** ~10× по-евтин и ~10× по-лек self-host. Това е реалното
оръжие.

## Фокусът за успех

Не се опитвайте да биете Sentry на breadth. Спечелете на **три неща**:

1. **"5-минутно onboarding-to-first-event"** — Sentry-то отнема 30+ мин с Wizard
   диалози. Ваша мерилна единица: time-to-first-event под 5 мин. Документация +
   copy-paste snippet, без акаунт wizard.
2. **Цена която не наказва успеха** — Pro $9 / 100k events е силна, но рисково ако
   usage-based след това пробие. Дръжте предвидим pricing като "anchor" —
   комуникирайте го агресивно ("no surprise bills").
3. **"Just errors, done well"** — не гонете performance/replay в P6. Победата е
   "по-просто и по-евтино от Sentry за 90% от small teams". Фичи се добавят, ако
   някой плаща за тях.

## Какво да направим по-добре

- **Issue grouping** — провери има ли реален fingerprint algorithm (stack-frame
  normalization, message templating). Ако не, това е #1 за P6.
- **Regression detection** — "тази грешка се появи отново след release v1.2.3" е
  малка фича но огромен retention driver.
- **Slack/Discord alerting от ден 1** — email-ите никой не ги чете в 2026.
- **Public status page + post-mortems** — Sentry губи доверие при инциденти,
  защото е closed-box. Wherever възможно — be transparent.
- **CLI-first DX** — `arguslog releases new` / `arguslog sourcemaps upload` трябва да са
  по-приятни от `sentry-cli`.

## Кои users да привлечем от Ден 1

Не таргетирайте Sentry потребителите — те имат inertia. Таргетирайте:

1. **Solo индията / 2–5 души startup-и** — Sentry им е твърде скъп, console.log-овете
   не им стигат. Tier-ът Free 5k и Pro $9 е точно за тях.
2. **Bulgarian/EU dev shops** — local data residency + EUR billing + български
   support = осезаема слабост на Sentry. Това е nis-таргет който никой не покрива.
3. **Java/Spring shops специфично** — имате собствен SDK + autoconfig. Sentry's
   Java SDK е "ok but generic". Ако стане default-ът за Spring Boot regional shops
   — страхотна вписка.
4. **Side-project / hobby developers** — Free tier-ът е щедър. Ловят се през Show HN,
   Reddit r/webdev, Bulgarian dev общности.

**Ден-1 marketing tactic:** един перфектен blog post — _"Self-hosted error tracking
for under $20/mo"_ с benchmark vs Sentry (honest, във ваша полза). HN front page =
1000 signup-а. Имате SDK дистрибуция (npm + Maven Central), k6 baseline и dogfood —
суровината за такъв пост вече е готова.
