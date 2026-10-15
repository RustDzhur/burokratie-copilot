'use client';

import { useState } from 'react';
import { Button } from '@/components/ui/button';
import { MobileCapture } from '@/components/ui/mobile-capture';
import { useTranslations } from '@/i18n/provider';
import { buildPdf, type PdfPage } from '@/lib/capture/images-to-pdf';
import { fileToPdfPage } from '@/lib/capture/to-jpeg';

export interface MultiPageCaptureProps {
  /** Receives the finished PDF, to be uploaded like any file. */
  readonly onPdf: (file: File) => void;
  /** Turns a picture into a page; replaceable for tests, the browser conversion by default. */
  readonly convert?: (file: File) => Promise<PdfPage>;
}

/** Take several pictures of a letter, put them in order, and hand them on as one PDF: the pages stay together. */
export function MultiPageCapture({ onPdf, convert = fileToPdfPage }: MultiPageCaptureProps) {
  const t = useTranslations('scan');
  const tCommon = useTranslations('common');
  const [shots, setShots] = useState<readonly File[]>([]);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState('');

  const move = (index: number, by: -1 | 1): void => {
    const target = index + by;
    if (target < 0 || target >= shots.length) return;
    const a = shots[index];
    const b = shots[target];
    if (!a || !b) return;
    const next = [...shots];
    next[index] = b;
    next[target] = a;
    setShots(next);
  };

  const finish = async (): Promise<void> => {
    setBusy(true);
    setProblem('');
    try {
      const pages: PdfPage[] = [];
      for (const shot of shots) pages.push(await convert(shot));
      const bytes = buildPdf(pages);
      onPdf(new File([bytes.buffer as ArrayBuffer], `Scan-${new Date().toISOString().slice(0, 10)}.pdf`, { type: 'application/pdf' }));
      setShots([]);
    } catch {
      setProblem(t('multi.failed'));
    } finally {
      setBusy(false);
    }
  };

  return (
    <section aria-labelledby="multi-capture-title" data-testid="multi-capture">
      <h3 id="multi-capture-title">{t('multi.title')}</h3>
      <p>{t('multi.hint')}</p>
      <MobileCapture onCapture={(files) => setShots((current) => [...current, ...files])} label={tCommon('actions.capture')} />
      {shots.length > 0 ? (
        <>
          <ol aria-label={t('multi.pages')}>
            {shots.map((shot, index) => (
              <li key={`${shot.name}-${index}`}>
                {t('multi.page', { number: index + 1 })} · {shot.name}{' '}
                <Button variant="ghost" aria-label={t('multi.up', { number: index + 1 })} disabled={index === 0} onClick={() => move(index, -1)}>
                  ↑
                </Button>
                <Button
                  variant="ghost"
                  aria-label={t('multi.down', { number: index + 1 })}
                  disabled={index === shots.length - 1}
                  onClick={() => move(index, 1)}
                >
                  ↓
                </Button>
                <Button variant="ghost" aria-label={t('multi.remove', { number: index + 1 })} onClick={() => setShots(shots.filter((_, i) => i !== index))}>
                  ✕
                </Button>
              </li>
            ))}
          </ol>
          <Button variant="primary" loading={busy} onClick={() => void finish()}>
            {t('multi.finish', { count: shots.length })}
          </Button>
        </>
      ) : null}
      <div aria-live="polite">{problem ? <p role="alert">{problem}</p> : null}</div>
    </section>
  );
}
