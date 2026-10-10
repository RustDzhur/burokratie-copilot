/**
 * Locale-aware formatting helpers.
 *
 * Every user-visible date, number and amount in the interface goes through these
 * functions so that German formatting (dd.MM.yyyy, 1.234,56 €) is consistent and
 * screen-reader friendly (`formatDateTimeParts` powers <time dateTime="…">).
 *
 * Machine-readable values stay ISO-8601 (`YYYY-MM-DD`) in state and API payloads.
 */

import { APP_TIME_ZONE, intlLocales, type Locale } from './config';

export type DateInput = Date | string | number;
export type DateStyleName = 'short' | 'medium' | 'long' | 'full';
export type TimeStyleName = 'short' | 'medium';

function toDate(value: DateInput): Date | null {
  const date =
    value instanceof Date ? value : new Date(typeof value === 'number' ? value : String(value));
  return Number.isNaN(date.getTime()) ? null : date;
}

/**
 * German administrative date format: dd.MM.yyyy (four-digit year).
 *
 * `dateStyle: 'short'` is deliberately not used for the short form, because
 * de-DE renders it as dd.MM.yy — the product requires dd.MM.yyyy (docs/task.md
 * §24). Longer styles still come from Intl.
 */
export function formatDate(
  value: DateInput,
  locale: Locale,
  style: DateStyleName = 'short',
): string {
  const date = toDate(value);
  if (!date) return '';
  const options: Intl.DateTimeFormatOptions =
    style === 'short'
      ? { day: '2-digit', month: '2-digit', year: 'numeric' }
      : { dateStyle: style };
  return new Intl.DateTimeFormat(intlLocales[locale], {
    ...options,
    timeZone: APP_TIME_ZONE,
  }).format(date);
}

export function formatTime(
  value: DateInput,
  locale: Locale,
  style: TimeStyleName = 'short',
): string {
  const date = toDate(value);
  if (!date) return '';
  return new Intl.DateTimeFormat(intlLocales[locale], {
    timeStyle: style,
    timeZone: APP_TIME_ZONE,
  }).format(date);
}

export function formatDateTime(
  value: DateInput,
  locale: Locale,
  options: { dateStyle?: DateStyleName; timeStyle?: TimeStyleName } = {},
): string {
  const date = toDate(value);
  if (!date) return '';
  return new Intl.DateTimeFormat(intlLocales[locale], {
    dateStyle: options.dateStyle ?? 'medium',
    timeStyle: options.timeStyle ?? 'short',
    timeZone: APP_TIME_ZONE,
  }).format(date);
}

export function formatNumber(
  value: number,
  locale: Locale,
  options: Intl.NumberFormatOptions = {},
): string {
  if (!Number.isFinite(value)) return '';
  return new Intl.NumberFormat(intlLocales[locale], options).format(value);
}

/** German default: `1.234,56 €`. Currency is never invented — callers pass it. */
export function formatCurrency(
  value: number,
  locale: Locale,
  currency = 'EUR',
  options: Intl.NumberFormatOptions = {},
): string {
  if (!Number.isFinite(value)) return '';
  return new Intl.NumberFormat(intlLocales[locale], {
    style: 'currency',
    currency,
    ...options,
  }).format(value);
}

export function formatPercent(value: number, locale: Locale, fractionDigits = 0): string {
  if (!Number.isFinite(value)) return '';
  return new Intl.NumberFormat(intlLocales[locale], {
    style: 'percent',
    minimumFractionDigits: fractionDigits,
    maximumFractionDigits: fractionDigits,
  }).format(value);
}

export function formatList(items: readonly string[], locale: Locale): string {
  if (items.length === 0) return '';
  return new Intl.ListFormat(intlLocales[locale], { style: 'long', type: 'conjunction' }).format(
    items,
  );
}

const RELATIVE_UNITS: ReadonlyArray<{ unit: Intl.RelativeTimeFormatUnit; ms: number }> = [
  { unit: 'year', ms: 365 * 24 * 60 * 60 * 1000 },
  { unit: 'month', ms: 30 * 24 * 60 * 60 * 1000 },
  { unit: 'week', ms: 7 * 24 * 60 * 60 * 1000 },
  { unit: 'day', ms: 24 * 60 * 60 * 1000 },
  { unit: 'hour', ms: 60 * 60 * 1000 },
  { unit: 'minute', ms: 60 * 1000 },
  { unit: 'second', ms: 1000 },
];

export function formatRelativeTime(
  value: DateInput,
  locale: Locale,
  base: Date = new Date(),
): string {
  const date = toDate(value);
  if (!date) return '';
  const deltaMs = date.getTime() - base.getTime();
  const formatter = new Intl.RelativeTimeFormat(intlLocales[locale], { numeric: 'auto' });
  for (const { unit, ms } of RELATIVE_UNITS) {
    if (Math.abs(deltaMs) >= ms || unit === 'second') {
      return formatter.format(Math.round(deltaMs / ms), unit);
    }
  }
  return '';
}

/** Byte sizes with German decimal separators (`1,4 MB`). */
export function formatFileSize(bytes: number, locale: Locale): string {
  if (!Number.isFinite(bytes) || bytes < 0) return '';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let value = bytes;
  let unitIndex = 0;
  while (value >= 1024 && unitIndex < units.length - 1) {
    value /= 1024;
    unitIndex += 1;
  }
  const fractionDigits = unitIndex === 0 ? 0 : value < 10 ? 1 : 0;
  return `${formatNumber(value, locale, {
    minimumFractionDigits: fractionDigits,
    maximumFractionDigits: fractionDigits,
  })} ${units[unitIndex] ?? 'B'}`;
}

/** `YYYY-MM-DD` in the configured time zone — the wire format for date fields. */
export function toIsoDate(value: DateInput): string {
  const date = toDate(value);
  if (!date) return '';
  const parts = new Intl.DateTimeFormat('en-CA', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    timeZone: APP_TIME_ZONE,
  }).format(date);
  return parts;
}

/** Parses `YYYY-MM-DD` into a local Date at noon (avoids DST edge cases). */
export function fromIsoDate(value: string | null | undefined): Date | null {
  if (!value) return null;
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value.trim());
  if (!match?.[1] || !match[2] || !match[3]) {
    const fallback = new Date(value);
    return Number.isNaN(fallback.getTime()) ? null : fallback;
  }
  const date = new Date(
    Number.parseInt(match[1], 10),
    Number.parseInt(match[2], 10) - 1,
    Number.parseInt(match[3], 10),
    12,
    0,
    0,
    0,
  );
  return Number.isNaN(date.getTime()) ? null : date;
}

/** Parses the German short form `dd.MM.yyyy` (and `d.M.yy`) typed by users. */
export function parseDisplayDate(value: string): Date | null {
  const trimmed = value.trim();
  const german = /^(\d{1,2})\.(\d{1,2})\.(\d{2,4})$/.exec(trimmed);
  if (german?.[1] && german[2] && german[3]) {
    const year =
      german[3].length === 2
        ? 2000 + Number.parseInt(german[3], 10)
        : Number.parseInt(german[3], 10);
    const date = new Date(
      year,
      Number.parseInt(german[2], 10) - 1,
      Number.parseInt(german[1], 10),
      12,
    );
    return Number.isNaN(date.getTime()) ? null : date;
  }
  return fromIsoDate(trimmed);
}

export function startOfMonth(date: Date): Date {
  return new Date(date.getFullYear(), date.getMonth(), 1);
}

export function addMonths(date: Date, amount: number): Date {
  return new Date(date.getFullYear(), date.getMonth() + amount, 1);
}

export function addDays(date: Date, amount: number): Date {
  const next = new Date(date);
  next.setDate(next.getDate() + amount);
  return next;
}

export function isSameDay(a: Date, b: Date): boolean {
  return (
    a.getFullYear() === b.getFullYear() &&
    a.getMonth() === b.getMonth() &&
    a.getDate() === b.getDate()
  );
}

export function isToday(date: Date, base: Date = new Date()): boolean {
  return isSameDay(date, base);
}

/** Month grid (Monday first) used by the DatePicker and the calendar screen. */
export function buildMonthMatrix(month: Date): Date[][] {
  const first = startOfMonth(month);
  const offset = (first.getDay() + 6) % 7; // Monday = 0
  const start = addDays(first, -offset);
  const weeks: Date[][] = [];
  for (let week = 0; week < 6; week += 1) {
    const days: Date[] = [];
    for (let day = 0; day < 7; day += 1) {
      days.push(addDays(start, week * 7 + day));
    }
    weeks.push(days);
  }
  return weeks;
}
