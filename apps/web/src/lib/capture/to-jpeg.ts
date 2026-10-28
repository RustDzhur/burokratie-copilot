import type { PdfPage } from './images-to-pdf';

/** The longest side a picture keeps: a phone photo of 12 megapixels would make a PDF nobody can upload. */
const MAX_SIDE = 2200;

/**
 * A picture the browser can show becomes a JPEG of reasonable size. The browser applies the orientation recorded by the
 * camera, so a letter photographed upright stays upright.
 */
export async function fileToPdfPage(file: File): Promise<PdfPage> {
  const bitmap = await createImageBitmap(file, { imageOrientation: 'from-image' });
  try {
    const scale = Math.min(1, MAX_SIDE / Math.max(bitmap.width, bitmap.height));
    const width = Math.max(1, Math.round(bitmap.width * scale));
    const height = Math.max(1, Math.round(bitmap.height * scale));
    const canvas = document.createElement('canvas');
    canvas.width = width;
    canvas.height = height;
    const context = canvas.getContext('2d');
    if (!context) throw new Error('A canvas is not available');
    context.fillStyle = '#ffffff'; // transparency would turn black in a JPEG
    context.fillRect(0, 0, width, height);
    context.drawImage(bitmap, 0, 0, width, height);
    const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/jpeg', 0.85));
    if (!blob) throw new Error('The picture could not be converted');
    return { jpeg: new Uint8Array(await blob.arrayBuffer()), width, height };
  } finally {
    bitmap.close();
  }
}
