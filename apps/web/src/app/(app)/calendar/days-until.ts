/**
 * Whole calendar days from today to an ISO date, negative when the date has passed.
 *
 * <p>Counted in days, not in hours: a deadline "tomorrow" is one day away at nine in the morning and one day
 * away at eleven at night, and a screen that says "0 days" at 23:59 is a screen nobody trusts. The urgency
 * thresholds themselves (calm, soon, critical) live where they are shown, because only the screen knows how it
 * wants to warn.</p>
 */
export function daysUntil(isoDate: string): number {
  const parts = isoDate.slice(0, 10).split('-').map(Number);
  const year = parts[0] ?? 0;
  const month = parts[1] ?? 1;
  const day = parts[2] ?? 1;
  const target = Date.UTC(year, month - 1, day);
  const now = new Date();
  const today = Date.UTC(now.getFullYear(), now.getMonth(), now.getDate());
  return Math.round((target - today) / 86_400_000);
}
