import { describe, expect, it } from 'vitest';
import { formatMessage } from '../icu';
import {
  formatCurrency,
  formatDate,
  formatDateTime,
  formatFileSize,
  formatNumber,
} from '../format';

describe('formatMessage', () => {
  it('interpolates named values', () => {
    expect(formatMessage('Hallo {name}!', { name: 'Anna' }, 'de')).toBe('Hallo Anna!');
  });

  it('renders empty string for missing values instead of leaking placeholders', () => {
    expect(formatMessage('Hallo {name}!', {}, 'de')).toBe('Hallo !');
  });

  it('selects German plural categories', () => {
    const pattern = '{count, plural, =0 {Keine Einträge} one {# Eintrag} other {# Einträge}}';
    expect(formatMessage(pattern, { count: 0 }, 'de')).toBe('Keine Einträge');
    expect(formatMessage(pattern, { count: 1 }, 'de')).toBe('1 Eintrag');
    expect(formatMessage(pattern, { count: 7 }, 'de')).toBe('7 Einträge');
  });

  it('selects Russian plural categories', () => {
    const pattern =
      '{count, plural, =0 {Нет} one {# запись} few {# записи} many {# записей} other {# записи}}';
    expect(formatMessage(pattern, { count: 1 }, 'ru')).toBe('1 запись');
    expect(formatMessage(pattern, { count: 3 }, 'ru')).toBe('3 записи');
    expect(formatMessage(pattern, { count: 11 }, 'ru')).toBe('11 записей');
  });

  it('supports select branches', () => {
    const pattern = '{gender, select, female {Sie} male {Er} other {Die Person}}';
    expect(formatMessage(pattern, { gender: 'female' }, 'de')).toBe('Sie');
    expect(formatMessage(pattern, { gender: 'unknown' }, 'de')).toBe('Die Person');
  });

  it('formats numbers, dates and currency with the active locale', () => {
    expect(formatMessage('{amount, number}', { amount: 1234.5 }, 'de')).toBe('1.234,5');
    expect(formatMessage('{amount, number, currency:EUR}', { amount: 1234.5 }, 'de')).toContain(
      '1.234,50',
    );
    expect(formatMessage('{when, date, short}', { when: '2026-01-15T10:00:00Z' }, 'de')).toBe(
      '15.01.2026',
    );
  });
});

describe('locale formatting', () => {
  it('formats German dates as dd.MM.yyyy', () => {
    expect(formatDate('2026-01-15T12:00:00Z', 'de')).toBe('15.01.2026');
    expect(formatDate('2026-01-15T12:00:00Z', 'en')).toBe('15/01/2026');
  });

  it('formats EUR amounts for the German market', () => {
    expect(formatCurrency(1234.56, 'de')).toBe('1.234,56\u00a0€');
    expect(formatCurrency(1234.56, 'en', 'EUR')).toContain('1,234.56');
  });

  it('formats numbers and file sizes', () => {
    expect(formatNumber(1234567.89, 'de')).toBe('1.234.567,89');
    expect(formatFileSize(1536, 'de')).toBe('1,5 KB');
  });

  it('formats date and time together', () => {
    const formatted = formatDateTime('2026-01-15T09:30:00Z', 'de');
    expect(formatted).toContain('15.01.2026');
    expect(formatted).toMatch(/\d{2}:\d{2}/);
  });
});
