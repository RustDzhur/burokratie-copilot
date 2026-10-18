import { describe, expect, it } from 'vitest';
import type { DateMarker } from '@/lib/api/types';
import {
  countDateMarkers,
  DATE_MARKER_KINDS,
  filterDateMarkers,
  groupDateMarkers,
  matchesDateMarkerQuery,
} from '../date-marker-model';

function marker(
  overrides: Partial<DateMarker> & Pick<DateMarker, 'id' | 'kind' | 'phrase'>,
): DateMarker {
  return {
    label: null,
    enabled: true,
    system: false,
    tenantOverride: false,
    ...overrides,
  };
}

const markers: readonly DateMarker[] = [
  marker({ id: 's1', kind: 'PAYMENT_DEADLINE', phrase: 'zahlungsziel', system: true }),
  marker({ id: 's2', kind: 'DEADLINE', phrase: 'spätestens', system: true }),
  marker({ id: 'o1', kind: 'APPOINTMENT', phrase: 'hausbesuch', label: 'Hausbesuch der Behörde' }),
  marker({ id: 'o2', kind: 'DOCUMENT_DATE', phrase: 'vom', enabled: false, tenantOverride: true }),
];

describe('filterDateMarkers', () => {
  it('finds a word by its phrase, ignoring case and surrounding spaces', () => {
    expect(filterDateMarkers(markers, { query: '  ZAHLUNG ' }).map((item) => item.id)).toEqual([
      's1',
    ]);
  });

  it('finds a word by the label a person gave it', () => {
    expect(filterDateMarkers(markers, { query: 'behörde' }).map((item) => item.id)).toEqual(['o1']);
  });

  it('narrows to one kind and keeps words when both filters admit them', () => {
    expect(filterDateMarkers(markers, { kind: 'APPOINTMENT' }).map((item) => item.id)).toEqual([
      'o1',
    ]);
    expect(filterDateMarkers(markers, { kind: 'DEADLINE', query: 'haus' })).toEqual([]);
    expect(filterDateMarkers(markers, { kind: 'ALL', query: '' })).toHaveLength(4);
  });

  it('matches an empty query everywhere', () => {
    expect(matchesDateMarkerQuery(markers[0]!, '   ')).toBe(true);
  });
});

describe('groupDateMarkers', () => {
  it('groups in taxonomy order and drops kinds without a word', () => {
    const groups = groupDateMarkers([...markers].reverse());
    expect(groups.map((group) => group.kind)).toEqual([
      'PAYMENT_DEADLINE',
      'APPOINTMENT',
      'DEADLINE',
      'DOCUMENT_DATE',
    ]);
    // The backend's order inside a kind is kept — the screen does not re-sort what the API ordered.
    expect(groups[0]!.items.map((item) => item.id)).toEqual(['s1']);
    expect(groups[2]!.items.map((item) => item.id)).toEqual(['s2']);
    expect(DATE_MARKER_KINDS).toHaveLength(6);
  });
});

describe('countDateMarkers', () => {
  it('counts a word that replaces a default as a platform default, so the default count stays stable', () => {
    const counts = countDateMarkers(markers);
    // s1 and s2 are defaults, o2 replaced the default "vom"; o1 is an own addition.
    expect(counts).toEqual({ standard: 3, own: 1, active: 3, total: 4 });
  });
});
