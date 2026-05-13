# Incidents

Each `## ` heading below is one incident. Order is newest first. Format:

```
## YYYY-MM-DDTHH:MMZ — Short title (severity)
Optional body paragraph describing what happened, what's affected, and where you are in
fixing it. Subsequent lines are concatenated into the description. Sub-bullets render verbatim.

- Affected: api, worker
- Status: resolved
- Duration: 12 min
```

The status page parses the headings + the first non-empty paragraph as the description. Sub-fields
(`Affected:`, `Status:`, `Duration:`) are optional and rendered as a small metadata strip.

When something breaks, add a new section at the very top, push to `main`, and the landing
deploy will pick it up within a few minutes. There's no admin UI on purpose — the markdown is
the audit trail.

---

<!-- INCIDENTS BELOW -->
