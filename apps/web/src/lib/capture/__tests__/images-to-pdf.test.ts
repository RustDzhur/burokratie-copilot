import { describe, expect, it } from 'vitest';
import { buildPdf, pageSize } from '../images-to-pdf';

const jpeg = (marker: number): Uint8Array => new Uint8Array([0xff, 0xd8, 0xff, 0xe0, marker, 0xff, 0xd9]);
const asText = (bytes: Uint8Array): string => new TextDecoder('latin1').decode(bytes);

describe('buildPdf', () => {
  it('writes one page per picture, in the given order, and carries each JPEG unchanged', () => {
    const pdf = buildPdf([
      { jpeg: jpeg(1), width: 800, height: 1000 },
      { jpeg: jpeg(2), width: 800, height: 1000 },
      { jpeg: jpeg(3), width: 800, height: 1000 },
    ]);
    const text = asText(pdf);
    expect(text.startsWith('%PDF-1.4')).toBe(true);
    expect(text).toContain('/Count 3');
    expect(text).toContain('/Kids [3 0 R 6 0 R 9 0 R]');
    expect(text.trimEnd().endsWith('%%EOF')).toBe(true);
    // each JPEG is inside the file as it came, in order
    const positions = [1, 2, 3].map((m) => text.indexOf(`ÿØÿà${String.fromCharCode(m)}ÿÙ`));
    expect(positions.every((p) => p > 0)).toBe(true);
    expect([...positions].sort((a, b) => a - b)).toEqual(positions);
  });

  it('points the cross-reference table at the real objects', () => {
    const pdf = buildPdf([{ jpeg: jpeg(7), width: 600, height: 800 }]);
    const text = asText(pdf);
    const start = Number(/startxref\n(\d+)\n/.exec(text)![1]);
    expect(text.slice(start, start + 4)).toBe('xref');
    const entries = [...text.slice(start).matchAll(/(\d{10}) 00000 n /g)].map((m) => Number(m[1]));
    expect(entries).toHaveLength(5); // catalog, pages, page, content, image
    entries.forEach((offset, index) => expect(text.slice(offset, offset + 7)).toBe(`${index + 1} 0 obj`));
  });

  it('keeps small pictures at their size and fits big ones onto A4, portrait or landscape', () => {
    expect(pageSize(300, 400)).toEqual({ width: 300, height: 400 });
    const portrait = pageSize(3000, 4000);
    expect(portrait.width).toBeLessThanOrEqual(595);
    expect(portrait.height).toBeLessThanOrEqual(842);
    const landscape = pageSize(4000, 3000);
    expect(landscape.width).toBeLessThanOrEqual(842);
    expect(landscape.height).toBeLessThanOrEqual(595);
    expect(Math.abs(portrait.width / portrait.height - 0.75)).toBeLessThan(0.01);
  });

  it('refuses an empty list and anything that is not a JPEG', () => {
    expect(() => buildPdf([])).toThrow();
    expect(() => buildPdf([{ jpeg: new Uint8Array([1, 2, 3, 4, 5]), width: 10, height: 10 }])).toThrow();
    expect(() => buildPdf([{ jpeg: jpeg(1), width: 0, height: 10 }])).toThrow();
  });
});
