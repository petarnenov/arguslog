/**
 * Builds the tooltip string for a capability-gated button. Kept as its own helper
 * (rather than inlining the join) so every gated screen renders the same wording, and
 * a future i18n pass has a single string to translate.
 *
 *   formatMissingTools([])                            → null  (caller renders no tooltip)
 *   formatMissingTools(['create_release'])            → "Requires: create_release"
 *   formatMissingTools(['triage_issue','assign_issue'])
 *                                                     → "Requires: triage_issue, assign_issue"
 */
export function formatMissingTools(missing: string[]): string | null {
  if (missing.length === 0) return null;
  return `Requires: ${missing.join(', ')}`;
}
