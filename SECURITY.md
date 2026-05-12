# Security Policy

## Reporting a vulnerability

Please **do not** open public GitHub issues for security-impacting bugs.

Email `security@arguslog.org` with:

- A description of the vulnerability + the impact you've assessed.
- Steps to reproduce (PoC code, requests, screenshots — whatever applies).
- The version / commit SHA you're testing against.
- Optionally, a suggested fix.

We acknowledge reports within 72 hours and aim to ship a fix within 14 days
for critical issues. For lower-severity issues we'll coordinate a timeline
in the reply.

## Scope

In scope for security reporting:

- The hosted instance at `arguslog.org` and its public subdomains.
- The code in this repository (when self-hosted with reasonable defaults).
- The SDKs published under `@arguslog/*` and `org.arguslog:*`.

Out of scope:

- Findings that require physical access to a self-hoster's infrastructure.
- Issues caused by self-hoster misconfiguration (e.g. running the dev-default
  master key in production, exposing Keycloak admin to the public internet
  without a reverse proxy).
- Denial-of-service against the hosted instance via traffic volume — use
  the API rate limits as documented; reach out via email if you've found
  a way to evade them with a small request count.

## Hall of fame

Reporters who let us coordinate disclosure get credit in the release notes
(opt-out available). We don't currently have a paid bounty program.
