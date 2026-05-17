# Web Store listing copy — Arguslog MCP Console 1.0.0

Paste these into the
[Chrome Web Store Developer Dashboard](https://chrome.google.com/webstore/devconsole)
when submitting. Operator-iterate; the strings below are a starting point — adjust
for brand voice and any post-launch feedback before submission.

## Category

Developer Tools

## Languages

English (default). More locales land via `public/_locales/<lang>/messages.json` per the
i18n scaffolding shipped in 1.0.0.

## Short description (max 132 characters)

> Browse Arguslog issues, run MCP workflows, triage releases — operator console for the
> open-source error tracker, in your side panel.

(131 characters — under the cap.)

## Detailed description

> **Arguslog MCP Console** is the operator's side panel for the open-source
> [Arguslog](https://arguslog.org) error-tracking platform. It pairs with your
> self-hosted (or arguslog.org-hosted) Arguslog instance and lets you triage issues,
> run Model Context Protocol workflows, and inspect releases without leaving the tab
> you're currently working in.
>
> **Built for operators who already use Arguslog.** This extension consumes the same
> MCP server your team's AI agents already talk to — no second account, no second
> credential. Bring your Personal Access Token and connect; everything from the
> dashboard is one keystroke away.
>
> ## What you can do
>
> - **Browse issues** with filters for status, level, and free-text search.
>   The active project follows the tab you're on if the URL is an arguslog.org issue
>   detail.
> - **Triage issues** with the same resolve / ignore / reopen affordances the dashboard
>   exposes — and assign them to a teammate without a context switch.
> - **Run Read · Eval · Triage · Loop workflows** end-to-end from the side panel: the
>   triage loop walks the unresolved queue, the regression check diffs two releases,
>   the postmortem assembles a markdown doc from a release's new issues.
> - **Inspect tool catalogs** with capability gating — disabled affordances surface
>   why (missing MCP tool, scope-restricted PAT) instead of failing silently when
>   clicked.
> - **Re-run any tool from history** with one click; arguments pre-fill so iteration
>   on a tricky payload doesn't require copy-paste from the previous run.
>
> ## Privacy and trust
>
> - **Your data stays yours.** The extension talks only to the Arguslog endpoint you
>   configure (default: `mcp.arguslog.org`). No analytics, no remote feature flags, no
>   third-party CDN.
> - **PAT encrypted at rest.** AES-GCM with a per-install salt. Session-only storage
>   is one toggle away if you want the credential gone the moment you close Chrome.
> - **Minimum permissions.** No `<all_urls>`, no `tabs`, no `webRequest`. Host access
>   is limited to `arguslog.org` for the content-script context publishing.
> - **Open source.** The full source tree lives at
>   [github.com/petarnenov/arguslog](https://github.com/petarnenov/arguslog) — inspect
>   what runs in your browser.
>
> Privacy policy: <https://arguslog.org/privacy/browser-extension>
>
> ## Requirements
>
> - Chrome 116 or later (side panel API).
> - An Arguslog instance with an MCP endpoint reachable from your network.
> - A Personal Access Token issued from the Arguslog dashboard.

## Justification text (per-permission, requested by Web Store reviewers)

- **`storage`** — Persists the operator's Personal Access Token (encrypted),
  workspace selection, and last 200 MCP tool executions for the History tab. No third
  party is ever sent this data.
- **`activeTab`** — Reads the URL of the foreground tab when the user opens the side
  panel, so the panel can scope to the right Arguslog org / project / issue if the
  tab is already viewing the dashboard.
- **`clipboardWrite`** — Powers the "Copy" buttons on the History, Tools, and
  Settings screens (snippet copy, diagnostic JSON, generated commands).
- **`downloads`** — Exports diagnostic bundles and tool-result JSON when the operator
  explicitly clicks an export action.
- **`sidePanel`** — Renders the extension's UI in Chrome's side-panel surface.
- **Host permissions** (`mcp.arguslog.org/*`, `*.arguslog.org/*`) — The only outbound
  endpoints the extension contacts. `mcp.arguslog.org` carries MCP traffic;
  `*.arguslog.org` lets the content script read the current dashboard tab's URL to
  populate the org / project picker.

## Single purpose

The extension's single purpose is a Chrome-resident operator console for the
Arguslog error-tracking platform. Every feature serves that purpose — browsing
issues, running workflows, inspecting releases, and triaging tools available on the
operator's Arguslog instance via the Model Context Protocol.
