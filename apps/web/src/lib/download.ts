/**
 * Browser download helper for binary API responses (exports, original files).
 *
 * Object URLs are revoked immediately after the click so no blob stays alive in
 * memory; the filename comes from the caller because the backend filename is not
 * trusted for path characters.
 */
export function downloadBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  anchor.rel = 'noopener';
  document.body.append(anchor);
  anchor.click();
  anchor.remove();
  URL.revokeObjectURL(url);
}
