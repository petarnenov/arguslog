# Arguslog Mobile Design — Figma Plugin

Generates the Arguslog mobile-first design system in your active Figma file:

- **Design tokens preview** — colors, typography (Inter + JetBrains Mono), spacing scale
- **7 mobile screens** at 375×812:
  1. Login / Welcome
  2. Onboarding (5-min first event)
  3. Issues list
  4. Issue detail (stack trace, sparkline, actions)
  5. Releases (with regression detection)
  6. Alerts (destinations + rules)
  7. Plan & Billing (with predictable-pricing callout)

Aimed at **developers** (code blocks, mono type, precise stack-trace UI) and
**Product Owners** (release status, alert destinations, billing transparency).

## Build

```bash
cd tools/figma-plugin
npm install
npm run build      # produces code.js (referenced by manifest.json)
```

## Run in Figma

1. Open Figma desktop (the plugin runtime is the same for the web app, but
   importing local plugins is easiest in desktop).
2. **Plugins → Development → Import plugin from manifest…**
3. Pick `tools/figma-plugin/manifest.json`.
4. Open any file (or create a new one).
5. **Plugins → Development → Arguslog Mobile Design** → Run.

The plugin generates everything on the current page in ~2 seconds, then closes.
Re-running adds a fresh copy alongside the previous one.

## Iterating

```bash
npm run watch   # rebuild on change
```

In Figma → Plugins → Development → **Hot-reload plugin** (or just Run again
after each save).

## Fonts

The plugin uses **Inter** (always available in Figma) and one of **JetBrains
Mono → Roboto Mono → Source Code Pro → Inter** for code blocks (whichever it
finds first). No manual installation required.

## What's where in `code.ts`

| Section           | Purpose                                                            |
| ----------------- | ------------------------------------------------------------------ |
| `TOKENS`          | All colors, radii, spacing scale (single source of truth)          |
| `FONT LOADING`    | Loads Inter + best available mono with graceful fallback           |
| `HELPERS`         | `frame()`, `txt()`, `spacer()`, `divider()`, `dot()` builders      |
| `COMPONENTS`      | `pillBadge`, `chip`, `button`, `topBar`, `bottomTabBar`            |
| `ICONS`           | Geometric placeholders (search, filter, bell, cog, copy, share, …) |
| `SCREEN BUILDERS` | One `build*()` per screen (login, onboarding, issues, …)           |
| `DESIGN TOKENS`   | The visible cheat-sheet frame at the top of the layout             |
| `MAIN`            | Lays out all frames on the page and zooms the viewport in          |

To add a new screen, write `build<Screen>(): FrameNode` and append it to the
`screens` array in `main()`.
