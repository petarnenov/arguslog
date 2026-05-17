# Publishing the Arguslog MCP Console extension to the Chrome Web Store

Status snapshot as of **2026-05-17**, after the four-phase MV3 best-practices sync
(commits `1803b00`, `de35e08`, `f69c6ea`, `0ac3c10`). The extension is **one
screenshot-PR away** from being submittable.

Everything code-side is done. What's left below is operator-owned creative output and
one platform-side step (Developer Dashboard account, if not already in place).

## What's done (code side)

- ✅ **Manifest V3 compliant** — tightened permissions (`tabs` dropped in favour of
  `activeTab`), `minimum_chrome_version: 116` for the side-panel API.
- ✅ **Privacy policy** drafted at `PRIVACY.md` covering every `chrome.storage`
  blob the extension writes.
- ✅ **`homepage_url`** in the manifest points at
  `https://arguslog.org/privacy/browser-extension`.
- ✅ **Version `1.0.0`** in `package.json` (was `0.1.0`).
- ✅ **Bundle size** under all guard budgets — sidepanel entry chunk 27 KB,
  background.js 287 KB, total `.output` 920 KB.
- ✅ **i18n scaffolding** with `default_locale: 'en'` — extension name +
  description + 18 anchor UI strings localised. Chrome falls back to English
  for any other UI language, no `__MSG_…__` placeholders ever leak.
- ✅ **Icons** at all four required sizes (16 / 32 / 48 / 128) in
  `public/icons/`.
- ✅ **`store-assets/`** scaffolded with `README.md` (filename contract) and
  `listing.md` (short + detailed description draft).
- ✅ **Real-browser smoke test** (Playwright) verifying manifest stays MV3,
  sidebar nav is correct, and the deprecated "Connect" nav-link is gone.

## What remains — pre-submission checklist

### 1. Screenshots — required by Web Store

Capture 3-5 PNGs at **1280×800** (Chrome's side panel at default width) and commit
them under `apps/browser-extension/store-assets/screenshots/` with these exact
filenames (the listing copy and submission flow reference them by name):

| File                         | Scene                                                   | Why                                                        |
| ---------------------------- | ------------------------------------------------------- | ---------------------------------------------------------- |
| `01-issues-populated.png`    | Sidepanel on `/issues` after `make demo`, issues loaded | Primary use case — browse + triage without leaving the tab |
| `02-workflows-running.png`   | Workflows screen mid-run of `arguslog_triage_loop`      | Unique Read · Eval · Triage · Loop affordance              |
| `03-settings-pat-stored.png` | Settings with the green "PAT stored" badge visible      | Trust posture (encrypted at rest, no plaintext echo)       |
| `04-tools-gated.png`         | Tools screen with several 🔒-prefixed unavailable tools | Capability gating — distinguishes from generic MCP clients |

> ⚠️ Redact your own email / PAT / session-sensitive IDs before commit. The
> screenshot is the cover art the Web Store renders to every reviewer and visitor.

### 2. Host the privacy policy

`PRIVACY.md` is in the repo; the manifest's `homepage_url` already points at
`https://arguslog.org/privacy/browser-extension`. Make sure that URL returns HTTP
200 with the rendered policy before submission.

Implementation options (pick whichever fits the landing-site stack):

- New MDX page in `apps/landing/` rendering `PRIVACY.md`.
- Static markdown-to-HTML build step in the landing pipeline.
- Reverse-proxy redirect to the GitHub raw file (acceptable but less polished).

### 3. Finalise listing copy

`apps/browser-extension/store-assets/listing.md` is a working starting point. Before
submission, iterate it for:

- Brand voice alignment with arguslog.org's landing copy.
- Short description ≤ 132 characters (current draft: 131 chars).
- Detailed description: 4-6 paragraphs is the sweet spot for Web Store reviewers —
  too long is skimmed, too short reads underdeveloped.
- Per-permission justification text matches the manifest permissions exactly.

### 4. Optional but recommended — promo tiles

Without these the listing still ships, but the extension can't be featured on the
Web Store homepage / category pages.

- **Small tile** — `440×280` PNG or JPEG, no transparency. The one a reviewer might
  surface on a category page.
- **Marquee tile** — `1400×560` PNG or JPEG, no transparency. Used only if Google
  features the extension on the Web Store homepage (rare).

Reuse logo + colourway from `apps/landing/public/` if the brand kit lives there.

### 5. Chrome Web Store Developer account

One-time setup (skip if already done):

- Register at <https://chrome.google.com/webstore/devconsole>.
- **One-time fee**: $5 USD.
- Verify the developer email + domain ownership (`arguslog.org`).
- Optionally: link the developer profile to the GitHub source to surface the open-
  source badge on the listing.

## Submission flow

Once 1-3 above are done (4-5 are pre-reqs done once):

```bash
# 1. Clean production build
pnpm --filter @arguslog/browser-extension build

# 2. Smoke-test the build locally before paying for review time
chrome  # open chrome://extensions → Developer mode → Load unpacked
        # → select apps/browser-extension/.output/chrome-mv3/
        # walk through Connect / Workspace / Issues / Settings flows

# 3. Package for upload
pnpm --filter @arguslog/browser-extension zip
# → apps/browser-extension/.output/chrome-mv3.zip
```

In the [Developer Dashboard](https://chrome.google.com/webstore/devconsole):

1. **New item** → upload `chrome-mv3.zip`.
2. **Store listing**:
   - Short description: paste from `listing.md` "Short description" block.
   - Detailed description: paste the "Detailed description" block.
   - Category: **Developer Tools**.
   - Language: **English** (more locales follow when translations land).
3. **Privacy practices**:
   - Privacy policy URL: `https://arguslog.org/privacy/browser-extension`.
   - For each permission, paste the matching justification from `listing.md`'s
     "Justification text" section.
   - Single-purpose statement: paste from `listing.md`'s "Single purpose" block.
4. **Graphic assets**:
   - Upload all four PNGs from `store-assets/screenshots/`.
   - Upload promo tiles if produced.
5. **Submit for review**.

## Review timeline + common rejections

- **Auto-review**: typically **24-72 hours** for a first submission. Updates after
  approval are usually under **24 hours** unless permissions change.
- **The frequent rejection reasons, and where we already addressed each**:

| Reason                                     | Status                                               |
| ------------------------------------------ | ---------------------------------------------------- |
| Missing privacy policy                     | ✅ Draft committed; awaits hosting                   |
| Excessive permissions                      | ✅ Tightened in Phase A (no `tabs`, no `<all_urls>`) |
| Single-purpose violation                   | ✅ Single purpose stated explicitly in listing       |
| Insufficient metadata (icons, screenshots) | ⏳ Icons done; screenshots remain (item 1)           |
| Deceptive behaviour                        | ✅ Description matches actual functionality verbatim |
| Inline / remote-loaded scripts             | ✅ Strict default CSP, no remote loads, no eval      |
| Source map / unbundled files in zip        | ✅ WXT `pnpm zip` produces a clean bundle            |

## After approval — version updates

Subsequent releases reuse the same Developer Dashboard listing. Bump the version in
`package.json`, re-zip, upload to the existing item. Updates inherit the existing
listing copy + screenshots; refresh them when a major UX change ships.

Versioning convention: semver per
[`CHANGELOG.md`](../../CHANGELOG.md) — patch for bugfixes, minor for additive
features, major when the permission set or storage schema changes in a way the
operator must opt into.

## See also

- [`PRIVACY.md`](PRIVACY.md) — the privacy policy itself.
- [`store-assets/README.md`](store-assets/README.md) — directory layout + filename
  contract for the creative deliverables.
- [`store-assets/listing.md`](store-assets/listing.md) — Web Store listing copy
  template.
- [`CHANGELOG.md`](../../CHANGELOG.md) "browser-extension 1.0.0" section — full
  list of what landed in this release.
