/**
 * Minimal ICU MessageFormat subset.
 *
 * Supported syntax (enough for the product catalogues, ~1 kB instead of a
 * full ICU runtime dependency):
 *
 *   Hallo {name}                                   -> simple interpolation
 *   {count, plural, =0 {keine} one {# Dokument} other {# Dokumente}}
 *   {gender, select, female {Sie} male {Er} other {Die Person}}
 *   {amount, number, currency:EUR}                 -> Intl.NumberFormat
 *   {when, date, short}                            -> Intl.DateTimeFormat
 *   {when, time, short}
 *
 * `#` inside a plural branch is replaced by the formatted number.
 * Literal braces are not supported: catalogue strings are UI copy only.
 */

import { intlLocales, type Locale } from './config';
import { formatDate, formatTime, type DateStyleName, type TimeStyleName } from './format';

export type MessageValue = string | number | boolean | Date | null | undefined;
export type MessageValues = Record<string, MessageValue>;

export type MessageFormatter = (pattern: string, values?: MessageValues) => string;

const pluralRulesCache = new Map<string, Intl.PluralRules>();

function pluralRules(locale: Locale): Intl.PluralRules {
  const cached = pluralRulesCache.get(locale);
  if (cached) return cached;
  const created = new Intl.PluralRules(intlLocales[locale]);
  pluralRulesCache.set(locale, created);
  return created;
}

interface ParsedArgument {
  readonly name: string;
  readonly type: string | null;
  readonly style: string | null;
  readonly branches: ReadonlyMap<string, string>;
}

/** Finds the index of the `}` matching the `{` at `start`, honouring nesting. */
function findClosingBrace(pattern: string, start: number): number {
  let depth = 0;
  for (let index = start; index < pattern.length; index += 1) {
    const character = pattern[index];
    if (character === '{') depth += 1;
    else if (character === '}') {
      depth -= 1;
      if (depth === 0) return index;
    }
  }
  return -1;
}

/** Splits `a, b, c` on top-level commas only. */
function splitTopLevel(input: string): string[] {
  const parts: string[] = [];
  let depth = 0;
  let current = '';
  for (const character of input) {
    if (character === '{') depth += 1;
    if (character === '}') depth -= 1;
    if (character === ',' && depth === 0) {
      parts.push(current.trim());
      current = '';
      continue;
    }
    current += character;
  }
  parts.push(current.trim());
  return parts;
}

/** Parses `one {…} other {…}` branch lists. */
function parseBranches(input: string): Map<string, string> {
  const branches = new Map<string, string>();
  let index = 0;
  while (index < input.length) {
    while (index < input.length && /\s/.test(input[index] ?? '')) index += 1;
    const keyStart = index;
    while (index < input.length && input[index] !== '{' && !/\s/.test(input[index] ?? ''))
      index += 1;
    const key = input.slice(keyStart, index).trim();
    while (index < input.length && /\s/.test(input[index] ?? '')) index += 1;
    if (input[index] !== '{') break;
    const end = findClosingBrace(input, index);
    if (end === -1) break;
    if (key) branches.set(key, input.slice(index + 1, end));
    index = end + 1;
  }
  return branches;
}

function parseArgument(content: string): ParsedArgument {
  const parts = splitTopLevel(content);
  const name = parts[0] ?? '';
  const type = parts[1] ?? null;
  if (type === 'plural' || type === 'select' || type === 'selectordinal') {
    return {
      name,
      type,
      style: null,
      branches: parseBranches(parts.slice(2).join(',')),
    };
  }
  return { name, type, style: parts[2] ?? null, branches: new Map() };
}

function formatNumberValue(value: MessageValue, locale: Locale, style: string | null): string {
  if (typeof value !== 'number' || Number.isNaN(value)) return String(value ?? '');
  if (style && style.startsWith('currency:')) {
    const currency = style.slice('currency:'.length).trim() || 'EUR';
    return new Intl.NumberFormat(intlLocales[locale], {
      style: 'currency',
      currency,
    }).format(value);
  }
  if (style === 'percent') {
    return new Intl.NumberFormat(intlLocales[locale], { style: 'percent' }).format(value);
  }
  return new Intl.NumberFormat(intlLocales[locale]).format(value);
}

function toMessageDate(value: MessageValue): Date | string {
  if (value === null || value === undefined) return '';
  if (value instanceof Date) return value;
  const date = new Date(typeof value === 'number' ? value : String(value));
  return Number.isNaN(date.getTime()) ? String(value) : date;
}

function formatDateValue(value: MessageValue, locale: Locale, style: string | null): string {
  const date = toMessageDate(value);
  if (typeof date === 'string') return date;
  return formatDate(date, locale, (style ?? 'medium') as DateStyleName);
}

function formatTimeValue(value: MessageValue, locale: Locale, style: string | null): string {
  const date = toMessageDate(value);
  if (typeof date === 'string') return date;
  return formatTime(date, locale, (style ?? 'short') as TimeStyleName);
}

function pickPluralBranch(argument: ParsedArgument, count: number, locale: Locale): string {
  const exact = argument.branches.get(`=${count}`);
  if (exact !== undefined) return exact;
  const category = argument.type === 'selectordinal' ? 'other' : pluralRules(locale).select(count);
  const branch = argument.branches.get(category) ?? argument.branches.get('other');
  if (branch !== undefined) return branch;
  const first = argument.branches.values().next();
  return first.done ? '' : first.value;
}

function pickSelectBranch(argument: ParsedArgument, value: MessageValue): string {
  const key = String(value ?? '');
  const branch = argument.branches.get(key) ?? argument.branches.get('other');
  if (branch !== undefined) return branch;
  const first = argument.branches.values().next();
  return first.done ? '' : first.value;
}

/**
 * Formats an ICU-subset pattern. Unknown placeholders resolve to an empty string
 * so a translation gap can never leak a raw `{token}` into the interface.
 */
export function formatMessage(
  pattern: string,
  values: MessageValues = {},
  locale: Locale = 'de',
): string {
  let result = '';
  let index = 0;

  while (index < pattern.length) {
    const character = pattern[index];
    if (character !== '{') {
      result += character;
      index += 1;
      continue;
    }
    const end = findClosingBrace(pattern, index);
    if (end === -1) {
      result += pattern.slice(index);
      break;
    }
    const argument = parseArgument(pattern.slice(index + 1, end));
    const value = values[argument.name];

    if (argument.type === 'plural' || argument.type === 'selectordinal') {
      const count = typeof value === 'number' ? value : Number(value ?? 0);
      const branch = pickPluralBranch(argument, Number.isFinite(count) ? count : 0, locale);
      const rendered = formatMessage(branch, values, locale);
      result += rendered.replace(/#/g, formatNumberValue(count, locale, null));
    } else if (argument.type === 'select') {
      result += formatMessage(pickSelectBranch(argument, value), values, locale);
    } else if (argument.type === 'number') {
      result += formatNumberValue(value, locale, argument.style);
    } else if (argument.type === 'date') {
      result += formatDateValue(value, locale, argument.style);
    } else if (argument.type === 'time') {
      result += formatTimeValue(value, locale, argument.style);
    } else if (value === null || value === undefined) {
      result += '';
    } else {
      result += String(value);
    }

    index = end + 1;
  }

  return result;
}
