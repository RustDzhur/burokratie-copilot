import type { DateMarker, DateMarkerKind } from '@/lib/api/types';

/**
 * The kinds in the backend's taxonomy order (the order the API serves them in). A word is only ever
 * classified into one of these; `OTHER` exists for a date that belongs to none of them but is never a
 * word a workspace can add — the API refuses it.
 */
export const DATE_MARKER_KINDS: readonly DateMarkerKind[] = [
  'PAYMENT_DEADLINE',
  'OBJECTION_DEADLINE',
  'APPOINTMENT',
  'VALIDITY_END',
  'DEADLINE',
  'DOCUMENT_DATE',
];

export type KindFilter = 'ALL' | DateMarkerKind;

export interface DateMarkerGroup {
  readonly kind: DateMarkerKind;
  readonly items: readonly DateMarker[];
}

/** Case-insensitive substring match on the word itself and on the label a person may have given it. */
export function matchesDateMarkerQuery(marker: DateMarker, query: string): boolean {
  const needle = query.trim().toLowerCase();
  if (needle === '') return true;
  return (
    marker.phrase.toLowerCase().includes(needle) ||
    (marker.label ?? '').toLowerCase().includes(needle)
  );
}

/** Applies the search field and the kind filter; the order the API sent is kept inside a result. */
export function filterDateMarkers(
  markers: readonly DateMarker[],
  options: { readonly query?: string; readonly kind?: KindFilter } = {},
): readonly DateMarker[] {
  const query = options.query ?? '';
  const kind = options.kind ?? 'ALL';
  return markers.filter(
    (marker) => (kind === 'ALL' || marker.kind === kind) && matchesDateMarkerQuery(marker, query),
  );
}

/** Groups words by kind in taxonomy order and drops kinds that have no word after filtering. */
export function groupDateMarkers(markers: readonly DateMarker[]): readonly DateMarkerGroup[] {
  return DATE_MARKER_KINDS.map((kind) => ({
    kind,
    items: markers.filter((marker) => marker.kind === kind),
  })).filter((group) => group.items.length > 0);
}

export interface DateMarkerCounts {
  /** Platform defaults, including the ones a workspace replaced or switched off. */
  readonly standard: number;
  /** Words a workspace added on top of the defaults. */
  readonly own: number;
  readonly active: number;
  readonly total: number;
}

/**
 * Counts for the header. A row that replaces a default (`tenantOverride`) counts as one of the platform's
 * defaults, so "255 Standardwörter" stays true after a default was switched off — the row only changed
 * from `system` to `tenantOverride`.
 */
export function countDateMarkers(markers: readonly DateMarker[]): DateMarkerCounts {
  let standard = 0;
  let own = 0;
  let active = 0;
  for (const marker of markers) {
    if (marker.system || marker.tenantOverride) standard += 1;
    else own += 1;
    if (marker.enabled) active += 1;
  }
  return { standard, own, active, total: markers.length };
}
