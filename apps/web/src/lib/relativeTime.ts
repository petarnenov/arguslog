/**
 * Format an ISO timestamp as a localized relative-time string ("2 days ago", "3 hours ago",
 * "in 5 minutes"). Uses {@link Intl.RelativeTimeFormat} so the output matches the user's
 * locale. Falls back to the raw string for unparseable input — never throws into render.
 */
const UNITS: Array<{ unit: Intl.RelativeTimeFormatUnit; ms: number }> = [
  { unit: 'year', ms: 365 * 24 * 60 * 60 * 1000 },
  { unit: 'month', ms: 30 * 24 * 60 * 60 * 1000 },
  { unit: 'week', ms: 7 * 24 * 60 * 60 * 1000 },
  { unit: 'day', ms: 24 * 60 * 60 * 1000 },
  { unit: 'hour', ms: 60 * 60 * 1000 },
  { unit: 'minute', ms: 60 * 1000 },
  { unit: 'second', ms: 1000 },
];

export function formatRelativeTime(iso: string, locale = 'en'): string {
  const ts = Date.parse(iso);
  if (!Number.isFinite(ts)) return iso;
  const diffMs = ts - Date.now();
  const abs = Math.abs(diffMs);
  const unit: { unit: Intl.RelativeTimeFormatUnit; ms: number } = UNITS.find(
    (u) => abs >= u.ms,
  ) ?? { unit: 'second', ms: 1000 };
  const value = Math.round(diffMs / unit.ms);
  return new Intl.RelativeTimeFormat(locale, { numeric: 'auto' }).format(value, unit.unit);
}
