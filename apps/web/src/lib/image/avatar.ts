/**
 * Scales a chosen picture to a square of at most `size` pixels (cut from the middle) so that a photo of several megabytes
 * becomes a small avatar before it leaves the device. Where the browser cannot do it, the original is sent and the server
 * decides whether it is small enough.
 */
export async function squareAvatar(file: Blob, size = 256): Promise<Blob> {
  if (typeof document === 'undefined' || typeof createImageBitmap !== 'function') return file;
  try {
    const bitmap = await createImageBitmap(file);
    const side = Math.min(bitmap.width, bitmap.height);
    const canvas = document.createElement('canvas');
    canvas.width = size;
    canvas.height = size;
    const context = canvas.getContext('2d');
    if (!context) return file;
    context.drawImage(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side, 0, 0, size, size);
    bitmap.close?.();
    const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/webp', 0.9));
    return blob ?? file;
  } catch {
    return file;
  }
}
