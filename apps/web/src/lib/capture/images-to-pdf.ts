/**
 * Several pictures of a letter become one PDF (UI-CAP-01).
 *
 * The pictures are JPEG already (the browser converts what it was given, see `to-jpeg.ts`), and a PDF can carry a JPEG
 * as it is (DCTDecode), so nothing is re-encoded here and no library is needed. One page per picture, the page as large
 * as the picture at 72 dpi but never larger than A4, with the picture filling it.
 */

export interface PdfPage {
  /** The bytes of a JPEG file. */
  readonly jpeg: Uint8Array;
  readonly width: number;
  readonly height: number;
}

const A4_WIDTH = 595;
const A4_HEIGHT = 842;

const encoder = new TextEncoder();

function text(value: string): Uint8Array {
  return encoder.encode(value);
}

function concat(parts: readonly Uint8Array[]): Uint8Array {
  const out = new Uint8Array(parts.reduce((sum, part) => sum + part.length, 0));
  let offset = 0;
  for (const part of parts) {
    out.set(part, offset);
    offset += part.length;
  }
  return out;
}

/** The size of the page in points: the picture's shape, scaled down to fit A4 (portrait or landscape) if it is bigger. */
export function pageSize(width: number, height: number): { readonly width: number; readonly height: number } {
  const maxWidth = width > height ? A4_HEIGHT : A4_WIDTH;
  const maxHeight = width > height ? A4_WIDTH : A4_HEIGHT;
  const scale = Math.min(1, maxWidth / width, maxHeight / height);
  return { width: Math.round(width * scale), height: Math.round(height * scale) };
}

export function buildPdf(pages: readonly PdfPage[]): Uint8Array {
  if (pages.length === 0) throw new Error('A PDF needs at least one page');
  for (const page of pages) {
    if (!(page.width > 0 && page.height > 0) || page.jpeg.length < 4 || page.jpeg[0] !== 0xff || page.jpeg[1] !== 0xd8) {
      throw new Error('Every page needs a JPEG picture with a size');
    }
  }
  // Object numbers: 1 catalog, 2 pages, then three per page (page, content, image).
  const objects: Uint8Array[] = [];
  const kids = pages.map((_, index) => `${3 + index * 3} 0 R`).join(' ');
  objects.push(text('<< /Type /Catalog /Pages 2 0 R >>'));
  objects.push(text(`<< /Type /Pages /Kids [${kids}] /Count ${pages.length} >>`));
  pages.forEach((page, index) => {
    const size = pageSize(page.width, page.height);
    const pageNumber = 3 + index * 3;
    const contentNumber = pageNumber + 1;
    const imageNumber = pageNumber + 2;
    const content = `q ${size.width} 0 0 ${size.height} 0 0 cm /Im0 Do Q`;
    objects.push(
      text(
        `<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${size.width} ${size.height}] ` +
          `/Resources << /XObject << /Im0 ${imageNumber} 0 R >> >> /Contents ${contentNumber} 0 R >>`,
      ),
    );
    objects.push(text(`<< /Length ${content.length} >>\nstream\n${content}\nendstream`));
    objects.push(
      concat([
        text(
          `<< /Type /XObject /Subtype /Image /Width ${page.width} /Height ${page.height} /ColorSpace /DeviceRGB ` +
            `/BitsPerComponent 8 /Filter /DCTDecode /Length ${page.jpeg.length} >>\nstream\n`,
        ),
        page.jpeg,
        text('\nendstream'),
      ]),
    );
  });

  const header = text('%PDF-1.4\n%âãÏÓ\n');
  const parts: Uint8Array[] = [header];
  const offsets: number[] = [];
  let position = header.length;
  objects.forEach((body, index) => {
    offsets.push(position);
    const piece = concat([text(`${index + 1} 0 obj\n`), body, text('\nendobj\n')]);
    parts.push(piece);
    position += piece.length;
  });
  const xrefStart = position;
  let xref = `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`;
  for (const offset of offsets) xref += `${String(offset).padStart(10, '0')} 00000 n \n`;
  xref += `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${xrefStart}\n%%EOF\n`;
  parts.push(text(xref));
  return concat(parts);
}
