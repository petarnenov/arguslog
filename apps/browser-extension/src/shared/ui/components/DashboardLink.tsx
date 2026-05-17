/**
 * Tiny icon-only deep-link that opens an Arguslog dashboard page in a new tab. Used in
 * IssuesScreen + ReleasesScreen row clusters so the operator can jump straight from the
 * side panel to the full dashboard view (events, breadcrumbs, stack trace, …) with one
 * click. The sidepanel is narrow — text labels would eat a row's vertical budget, so
 * we render just the external-link glyph + a `title` tooltip.
 *
 * Inline SVG (rather than a Tabler / Lucide dep) — the extension has no icon library
 * today and we don't want to add one just for this single glyph.
 */
import type { MouseEvent } from 'react';

interface DashboardLinkProps {
  href: string;
  /** Optional label override — defaults to "Open in Arguslog dashboard". Localised here. */
  label?: string;
}

export function DashboardLink({ href, label = 'Open in Arguslog dashboard' }: DashboardLinkProps) {
  // The row containers in IssuesScreen / ReleasesScreen are themselves `<button>`s that
  // select the entity on click. Without `stopPropagation`, clicking the deep-link would
  // ALSO toggle the row selection — surprising and undesired.
  const stopRowSelect = (event: MouseEvent) => event.stopPropagation();

  return (
    <a
      href={href}
      target="_blank"
      rel="noopener noreferrer"
      title={label}
      aria-label={label}
      onClick={stopRowSelect}
      className="inline-flex h-6 w-6 items-center justify-center rounded-md text-slate-400 transition hover:bg-slate-800 hover:text-white"
    >
      <svg
        xmlns="http://www.w3.org/2000/svg"
        width="14"
        height="14"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        aria-hidden="true"
      >
        {/* external-link icon — minimal Feather/Lucide shape */}
        <path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6" />
        <polyline points="15 3 21 3 21 9" />
        <line x1="10" y1="14" x2="21" y2="3" />
      </svg>
    </a>
  );
}
