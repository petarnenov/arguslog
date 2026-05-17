/**
 * Tiny markdown parser for the incidents log. Pure function — no DOM, no third-party
 * dependency — because the format is constrained enough that a 30-line scanner is more
 * predictable than pulling in `marked` / `remark` for one file.
 *
 * Grammar (per incident, separated by `## ` headings):
 *   ## <ISO-timestamp> — <Title> (severity)
 *   <free-text description paragraph (optional)>
 *   - Affected: comma list (optional)
 *   - Status: resolved | monitoring | investigating | identified (optional)
 *   - Duration: free text (optional)
 *
 * The "<!-- INCIDENTS BELOW -->" sentinel separates explanatory header text from real data —
 * if it's missing, we still try to parse the whole file.
 */

export type IncidentSeverity = 'minor' | 'major' | 'critical' | 'unknown';
export type IncidentStatus = 'investigating' | 'identified' | 'monitoring' | 'resolved' | 'unknown';

export interface Incident {
  /** ISO 8601 timestamp parsed from the heading. */
  startedAt: string;
  title: string;
  severity: IncidentSeverity;
  description: string;
  affected: string[];
  status: IncidentStatus;
  duration?: string;
}

const SENTINEL = '<!-- INCIDENTS BELOW -->';

export function parseIncidents(markdown: string): Incident[] {
  const body = markdown.includes(SENTINEL) ? (markdown.split(SENTINEL)[1] ?? '') : markdown;
  const sections = body.split(/\n##\s+/).slice(1); // first split is the prelude — drop it
  const out: Incident[] = [];
  for (const raw of sections) {
    const lines = raw.split('\n');
    const heading = lines[0]?.trim() ?? '';
    const parsed = parseHeading(heading);
    if (!parsed) continue;
    const rest = lines.slice(1).join('\n').trim();
    const { description, affected, status, duration } = parseBody(rest);
    out.push({
      ...parsed,
      description,
      affected,
      status,
      duration,
    });
  }
  // Markdown is newest-first by convention but we don't enforce — sort to be safe.
  out.sort((a, b) => b.startedAt.localeCompare(a.startedAt));
  return out;
}

function parseHeading(
  raw: string,
): { startedAt: string; title: string; severity: IncidentSeverity } | null {
  // "2026-05-13T18:42Z — Worker deploy failed (minor)"
  const match = raw.match(/^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}Z?)\s*[—-]\s*(.+?)(?:\s*\(([^)]+)\))?$/);
  if (!match) return null;
  return {
    startedAt: match[1]!,
    title: match[2]!.trim(),
    severity: parseSeverity(match[3]),
  };
}

function parseSeverity(raw: string | undefined): IncidentSeverity {
  switch ((raw ?? '').toLowerCase().trim()) {
    case 'minor':
      return 'minor';
    case 'major':
      return 'major';
    case 'critical':
      return 'critical';
    default:
      return 'unknown';
  }
}

function parseBody(rest: string): {
  description: string;
  affected: string[];
  status: IncidentStatus;
  duration?: string;
} {
  const lines = rest.split('\n');
  const descLines: string[] = [];
  let affected: string[] = [];
  let status: IncidentStatus = 'unknown';
  let duration: string | undefined;

  for (const line of lines) {
    const trimmed = line.trim();
    const meta = trimmed.match(/^[-*]\s+([A-Za-z]+):\s*(.+)$/);
    if (meta) {
      const key = meta[1]!.toLowerCase();
      const value = meta[2]!.trim();
      if (key === 'affected')
        affected = value
          .split(',')
          .map((s) => s.trim())
          .filter(Boolean);
      else if (key === 'status') status = parseStatus(value);
      else if (key === 'duration') duration = value;
    } else if (trimmed.length > 0) {
      descLines.push(trimmed);
    }
  }
  return {
    description: descLines.join(' ').trim(),
    affected,
    status,
    duration,
  };
}

function parseStatus(raw: string): IncidentStatus {
  switch (raw.toLowerCase().trim()) {
    case 'investigating':
      return 'investigating';
    case 'identified':
      return 'identified';
    case 'monitoring':
      return 'monitoring';
    case 'resolved':
      return 'resolved';
    default:
      return 'unknown';
  }
}
