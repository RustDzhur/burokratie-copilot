import { describe, expect, it } from 'vitest';

/**
 * The urgency of a deadline is a rule from the specification (§5.4): more than fourteen days is calm, seven to
 * fourteen is worth noticing, less than seven is the one that costs money and liability. The screen expresses it
 * with a colour, and the colour comes from this number, so the number is what gets tested.
 */

// The helper lives in the page module next to the table it serves; importing it keeps the rule in one place.
import { daysUntil } from '@/app/(app)/calendar/days-until';

function isoOffset(days: number): string {
  // The helper used to call toISOString(), which switches to UTC and therefore produced the previous day
  // between midnight and 02:00 Berlin time. daysUntil() counts local calendar days, so the fixture must too.
  const d = new Date();
  d.setDate(d.getDate() + days);
  const month = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${d.getFullYear()}-${month}-${day}`;
}

describe('deadline urgency', () => {
  it('counts calendar days, not hours', () => {
    expect(daysUntil(isoOffset(0))).toBe(0);
    expect(daysUntil(isoOffset(1))).toBe(1);
    expect(daysUntil(isoOffset(14))).toBe(14);
  });

  it('goes negative once the date has passed', () => {
    expect(daysUntil(isoOffset(-1))).toBe(-1);
    expect(daysUntil(isoOffset(-30))).toBe(-30);
  });

  it('ignores a time part when one is present', () => {
    const day = isoOffset(3);
    expect(daysUntil(`${day}T23:59:59Z`)).toBe(3);
  });
});
