/**
 * CSV export built from the same list endpoints the screens use.
 *
 * Nothing is exported that the caller could not already read on screen, and the tenant boundary and
 * permissions are enforced by the list endpoint rather than re-implemented here. Cells that start with a
 * formula character are prefixed, so a document called `=HYPERLINK(...)` cannot execute in a spreadsheet.
 */

import type { Page } from './client';

const EXPORT_PAGE_SIZE = 200;
/** An export is a convenience, not a backup: beyond this the user is told to narrow the question. */
export const EXPORT_ROW_LIMIT = 5000;

export type CsvCell = string | number | boolean | null | undefined;

export function csvCell(value: CsvCell): string {
  if (value === null || value === undefined) return '';
  let text = String(value);
  if (/^[=+\-@\t\r]/.test(text)) text = `'${text}`;
  return /[",\n\r;]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text;
}

export function toCsv<T>(
  rows: readonly T[],
  columns: readonly { readonly header: string; readonly value: (row: T) => CsvCell }[],
): string {
  const lines = [
    columns.map((column) => csvCell(column.header)).join(';'),
    ...rows.map((row) => columns.map((column) => csvCell(column.value(row))).join(';')),
  ];
  // BOM so that Excel opens the file as UTF-8 (German umlauts).
  return `\uFEFF${lines.join('\r\n')}\r\n`;
}

export function csvBlob(text: string): Blob {
  return new Blob([text], { type: 'text/csv;charset=utf-8' });
}

/** Reads every page of a list, up to {@link EXPORT_ROW_LIMIT} rows. */
export async function collectPages<T>(
  load: (page: number, size: number) => Promise<Page<T>>,
): Promise<T[]> {
  const rows: T[] = [];
  for (let page = 0; rows.length < EXPORT_ROW_LIMIT; page += 1) {
    const result = await load(page, EXPORT_PAGE_SIZE);
    rows.push(...result.items);
    if (page + 1 >= result.totalPages || result.items.length === 0) break;
  }
  return rows.slice(0, EXPORT_ROW_LIMIT);
}
