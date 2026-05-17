# Store assets

Creative deliverables required to submit the extension to the
[Chrome Web Store Developer Dashboard](https://chrome.google.com/webstore/devconsole).
This directory tracks them in version control so future updates regenerate from a
canonical source instead of "where did I save those screenshots?".

## Layout

```
store-assets/
├── README.md           ← this file
├── listing.md          ← short + long listing copy (operator-iterates)
├── screenshots/        ← 3-5 PNGs, 1280×800 preferred (640×400 acceptable)
└── promo/              ← optional promo tiles (440×280 small, 920×680 large)
```

## What still needs to be produced

| Asset                      | Required?                            | Spec                                             | Status                                       |
| -------------------------- | ------------------------------------ | ------------------------------------------------ | -------------------------------------------- |
| Privacy policy URL         | **Yes** (data-collecting extensions) | Public URL serving `PRIVACY.md` rendered as HTML | ✅ `arguslog.org/privacy/browser-extension`  |
| Listing description        | **Yes**                              | ≤132 char short + ≤16 000 char detailed          | ✅ template in `listing.md`                  |
| Icons (16 / 32 / 48 / 128) | **Yes**                              | PNG with alpha channel                           | ✅ in `apps/browser-extension/public/icons/` |
| Screenshots (≥1, max 5)    | **Yes**                              | 1280×800 _or_ 640×400 PNG, no transparency       | ⏳ operator-owned, see "Screenshots" below   |
| Small promo tile           | Optional                             | 440×280 PNG/JPEG, no transparency                | ⏳ optional                                  |
| Marquee promo              | Optional                             | 1400×560 PNG/JPEG, no transparency               | ⏳ optional                                  |

## Screenshots

Capture these four scenarios at 1280×800 (Chrome side panel at default width):

1. **`01-issues-populated.png`** — Sidepanel on `/issues` after seeding via
   `make demo`. Showcases the primary use case (browse + triage issues without
   leaving the current tab).
2. **`02-workflows-running.png`** — Workflows screen mid-run of
   `arguslog_triage_loop`. Showcases the Read · Eval · Triage · Loop affordance
   that no generic MCP client has.
3. **`03-settings-pat-stored.png`** — Settings screen with the "PAT stored" badge
   visible. Showcases trust posture (encrypted at rest, no plaintext echo).
4. **`04-tools-gated.png`** — Tools screen with a few 🔒-prefixed unavailable
   tools. Showcases capability gating (the only operator-facing surface that
   does this in the MCP ecosystem).

Each screenshot must:

- Show real-looking data, not the placeholder demo strings. The screenshot is
  what convinces a reviewer the extension does what its description claims.
- Avoid leaking any operator email, PAT, or session info — crop or redact
  before commit.
- Be added under `store-assets/screenshots/` with the filenames above so the
  listing-copy file can reference them by stable name.

## Promo tiles (optional but recommended)

A featured-extension placement on the Web Store needs the small tile; the
marquee is only used if Google highlights the extension on the homepage.

Reuse the Arguslog logo from
[`apps/landing/public/`](../../landing/public/) — confirm the colourway matches
the rest of the brand before commit.

## Workflow

1. Build the extension with `pnpm --filter @arguslog/browser-extension build`.
2. Load `.output/chrome-mv3/` via `chrome://extensions` → Developer mode →
   Load unpacked.
3. Capture the four screenshots above (Mac: ⌘⇧4 then space to grab a window;
   Windows: Win+Shift+S).
4. Commit them to `store-assets/screenshots/` in a separate PR titled
   `store(browser-extension): screenshots for 1.0.0 listing`.
5. Run `pnpm --filter @arguslog/browser-extension zip` to produce the upload
   archive, then submit via the Developer Dashboard with `listing.md` copy
   pasted in.

## Don't commit binaries via `lfs` unless the assets folder grows past 5 MB

Four PNGs at 1280×800 are typically 200-500 KB each. The repo isn't
LFS-configured today; if total `store-assets/` ever exceeds 5 MB consider
hosting the assets externally (R2 bucket the landing site already uses) and
keeping only thumbnails + URLs here.
