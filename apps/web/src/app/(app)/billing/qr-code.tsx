'use client';

import { useEffect, useState } from 'react';
import styles from './billing.module.css';

/** Draws a QR code as an image; the text is encoded in the browser and goes nowhere else. */
export function QrCode({ text, label }: { readonly text: string; readonly label: string }) {
  const [source, setSource] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    void import('qrcode')
      .then((module) => module.toDataURL(text, { margin: 1, width: 192, errorCorrectionLevel: 'M' }))
      .then((url) => {
        if (!cancelled) setSource(url);
      })
      .catch(() => {
        if (!cancelled) setSource(null);
      });
    return () => {
      cancelled = true;
    };
  }, [text]);

  // eslint-disable-next-line @next/next/no-img-element
  return source ? <img className={styles.qr} src={source} alt={label} /> : null;
}
