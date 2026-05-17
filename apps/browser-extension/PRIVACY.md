# Privacy Policy — Arguslog MCP Console (Chrome extension)

_Last updated: 2026-05-17._

This extension is a self-hostable operator console for the open-source
[Arguslog](https://arguslog.org) error-tracking platform. It connects only to the
Arguslog instance you point it at — there is no Arguslog-owned cloud service in the
data path.

This document describes what the extension stores, where it lives, and what is (and
isn't) shared with anyone.

## What the extension stores

All operator data lives in
[`chrome.storage`](https://developer.chrome.com/docs/extensions/reference/api/storage),
sandboxed per-extension and per-profile. Nothing is written to the wider filesystem,
no cookies are set, no IndexedDB is opened.

| Item                                                | Location                                                               | Encrypted at rest                                                                                                              | Purpose                                                                                                                                                                                      |
| --------------------------------------------------- | ---------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Personal access token (PAT)                         | `chrome.storage.local` _or_ `chrome.storage.session` (operator choice) | **Yes** — AES-GCM with a per-install salt derived via PBKDF2 (100 000 iterations). The plaintext key is never written to disk. | Authenticates every MCP request to the Arguslog instance you connected to.                                                                                                                   |
| Workspace selection                                 | `chrome.storage.local`                                                 | No (non-sensitive metadata)                                                                                                    | Tracks the active org / project so screens render the right data after a reload.                                                                                                             |
| Execution history                                   | `chrome.storage.local`                                                 | No (results truncated to 2 KB per entry; argument bodies stored verbatim because the operator needs them for "Rerun")          | Renders the History tab; capped at 200 entries with newest-first rotation.                                                                                                                   |
| Settings (endpoint, theme, debug, persistence-mode) | `chrome.storage.sync`                                                  | No                                                                                                                             | Survives the extension reload + syncs across the same Google account's Chromes if Chrome Sync is on for extensions. Endpoint string is the only Arguslog-related value here; no credentials. |
| Capability snapshot                                 | `chrome.storage.local`                                                 | No (server's public tool catalog)                                                                                              | Drives the per-tool gating in the UI so disabled affordances are obvious.                                                                                                                    |
| Page context                                        | `chrome.storage.session`                                               | No                                                                                                                             | Mirrors the org / project IDs the content script reads from the current `arguslog.org` tab URL. Cleared on browser restart.                                                                  |

## What is sent over the network

The extension makes outbound requests only to the **endpoint you typed in
Settings** (default: `https://mcp.arguslog.org/mcp`). Each request carries:

- Your PAT in an `Authorization: Bearer …` header.
- The MCP tool name + arguments you (or a workflow you started) selected.

No request body ever contains data from non-`arguslog.org` tabs, no analytics or
telemetry leaves your browser, and no third-party CDN is contacted.

## What is shared with Arguslog (the project / authors)

**Nothing.** The Arguslog project does not operate a hosted backend for this
extension. We do not collect crash reports, usage metrics, or any other phone-home
signal from the extension. If you self-host Arguslog, only you (and whoever you grant
PATs to) have access to the data the extension reads.

## What is shared with third parties

**Nothing.** The extension has no analytics, no ads, no remote feature flags, no
dependency on a Google API beyond Chrome's own storage / sidePanel surface.

## Permissions, justified

Per Chrome's [Web Store requirements](https://developer.chrome.com/docs/webstore/troubleshooting),
each requested permission has a use:

- `storage` — persist the items above.
- `activeTab` — read the URL of the current tab when the operator clicks the action
  icon, so the content script can detect an `arguslog.org` page and publish its
  org / project context to the side panel.
- `clipboardWrite` — copy snippets (DSN values, tool args, generated commands) when
  the operator clicks "Copy" affordances.
- `downloads` — export diagnostic bundles + tool-result JSON when the operator opts
  in.
- `sidePanel` — render the operator console.

Host permissions are restricted to `https://mcp.arguslog.org/*` (the default hosted
MCP endpoint) and `https://*.arguslog.org/*` (the dashboard, for content-script
context publishing). The extension does not request `<all_urls>`.

## Source code

This extension is open source under the same license as the rest of the
[Arguslog repository](https://github.com/petarnenov/arguslog). Anyone can inspect
exactly what runs in their browser.

## Updates to this policy

Material changes are announced in the repository CHANGELOG and the extension's
listing description. The "Last updated" date at the top of this file always reflects
the most recent revision.

## Contact

Privacy questions or concerns: open an issue at
[github.com/petarnenov/arguslog/issues](https://github.com/petarnenov/arguslog/issues)
or email **security@arguslog.org**.
