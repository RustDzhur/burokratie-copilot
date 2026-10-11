'use client';

import { useRef } from 'react';

/**
 * Taking a picture of a letter with a telephone (§5.2.21, B-015).
 *
 * This uses the file input with a capture hint rather than a camera preview drawn by this application.
 * That is a decision, not a shortcut. The browser's own camera interface already knows how to focus, how
 * to expose, how to rotate, how to ask for permission and how to work on the several hundred devices
 * this platform will meet; a preview built here would do all of that worse, and would be the reason
 * somebody's telephone cannot scan.
 *
 * What the application does control is what happens to the picture afterwards: it goes into the same
 * upload queue as a file, through the same ingestion and the same duplicate detection.
 */
export interface MobileCaptureProps {
  /** Called with the pictures that were taken. */
  onCapture: (files: File[]) => void;
  /** Whether the control is available at all. */
  disabled?: boolean;
  /** Which camera to open first; the rear one is the one pointed at paper. */
  facing?: 'environment' | 'user';
  label?: string;
  className?: string;
}

export function MobileCapture({
  onCapture,
  disabled = false,
  facing = 'environment',
  label = 'Foto aufnehmen',
  className,
}: MobileCaptureProps) {
  const input = useRef<HTMLInputElement>(null);

  return (
    <div className={className}>
      <input
        ref={input}
        type="file"
        // `accept` limits the picker to pictures, `capture` asks the system to open the camera directly.
        // A browser that does not know `capture` falls back to the ordinary file picker, which is the
        // correct behaviour rather than an error.
        accept="image/*"
        capture={facing}
        multiple
        disabled={disabled}
        // The visible button is the keyboard entry point. An input that is clipped away but still in the
        // tab order is a focus stop nobody can see.
        tabIndex={-1}
        // Hidden visually but not from the accessibility tree: a control removed with `display: none`
        // cannot be reached by a screen reader either, and this one is the whole point of the page.
        style={{
          position: 'absolute',
          width: '1px',
          height: '1px',
          padding: 0,
          margin: '-1px',
          overflow: 'hidden',
          clipPath: 'inset(50%)',
          whiteSpace: 'nowrap',
          border: 0,
        }}
        onChange={(event) => {
          const files = Array.from(event.target.files ?? []);
          if (files.length > 0) {
            onCapture(files);
          }
          // Cleared so that taking the same picture twice, or retaking one after a failure, still fires
          // a change event - a tired person photographing a letter should not have to reload the page.
          event.target.value = '';
        }}
      />
      <button type="button" onClick={() => input.current?.click()} disabled={disabled}>
        {label}
      </button>
    </div>
  );
}
