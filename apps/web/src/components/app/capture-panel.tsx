'use client';

import Link from 'next/link';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Button } from '@/components/ui/button';
import { Checkbox } from '@/components/ui/form';
import { MobileCapture } from '@/components/ui/mobile-capture';
import { useLocale, useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { toApiError } from '@/lib/api/errors';
import type { DocumentDetail, DocumentSummary } from '@/lib/api/types';
import { buildPdf, type PdfPage } from '@/lib/capture/images-to-pdf';
import { fileToPdfPage } from '@/lib/capture/to-jpeg';

const POLL_MS = 2500;
const POLL_LIMIT = 36; // about 90 seconds

type Phase = 'collecting' | 'working' | 'done';

interface Outcome {
  readonly id: string;
  readonly title: string;
  readonly detail: DocumentDetail | DocumentSummary;
  /** Whether the platform has finished looking at the document. */
  readonly settled: boolean;
}

export interface CapturePanelProps {
  /** Replaceable for tests; the browser conversion by default. */
  readonly convert?: (file: File) => Promise<PdfPage>;
  /** Called once the pictures have been uploaded. */
  readonly onUploaded?: () => void;
}

/**
 * Photographing a letter: take the pages one after another, put them in order, and save them as ONE document. After saving
 * the person sees what happens to it (reading, recognised type, or "please check") with a link to it, so the document is never
 * "somewhere". A page that is a document of its own can be saved separately.
 */
export function CapturePanel({ convert = fileToPdfPage, onUploaded }: CapturePanelProps) {
  const t = useTranslations('scan');
  const tCommon = useTranslations('common');
  const tErrors = useTranslations('errors');
  const locale = useLocale();
  const [pages, setPages] = useState<readonly File[]>([]);
  const [separate, setSeparate] = useState(false);
  const [phase, setPhase] = useState<Phase>('collecting');
  const [problem, setProblem] = useState('');
  const [outcomes, setOutcomes] = useState<readonly Outcome[]>([]);
  const alive = useRef(true);

  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
    };
  }, []);

  const previews = useMemo(
    () => pages.map((file) => (typeof URL !== 'undefined' && typeof URL.createObjectURL === 'function' ? URL.createObjectURL(file) : null)),
    [pages],
  );
  useEffect(
    () => () => {
      previews.forEach((url) => {
        if (url && typeof URL.revokeObjectURL === 'function') URL.revokeObjectURL(url);
      });
    },
    [previews],
  );

  const addPages = useCallback((files: readonly File[]) => setPages((current) => [...current, ...files]), []);

  const move = (index: number, by: -1 | 1): void => {
    const target = index + by;
    const a = pages[index];
    const b = pages[target];
    if (!a || !b) return;
    const next = [...pages];
    next[index] = b;
    next[target] = a;
    setPages(next);
  };

  const watch = useCallback(async (first: DocumentSummary): Promise<void> => {
    setOutcomes((current) => [...current.filter((o) => o.id !== first.id), { id: first.id, title: first.title, detail: first, settled: false }]);
    for (let attempt = 0; attempt < POLL_LIMIT && alive.current; attempt++) {
      await new Promise((resolve) => setTimeout(resolve, POLL_MS));
      try {
        const detail = await api.documents.get(first.id);
        const settled = detail.status !== 'received' && detail.status !== 'processing';
        if (!alive.current) return;
        setOutcomes((current) => current.map((o) => (o.id === first.id ? { ...o, detail, settled } : o)));
        if (settled) return;
      } catch {
        return; // the person can still open the document; the page does not nag about a failed check
      }
    }
  }, []);

  const save = async (): Promise<void> => {
    setPhase('working');
    setProblem('');
    try {
      const uploaded: DocumentSummary[] = [];
      if (separate || pages.length === 1) {
        for (const file of pages) {
          uploaded.push(await api.documents.upload(file, { idempotencyKey: true }));
        }
      } else {
        const converted: PdfPage[] = [];
        for (const file of pages) converted.push(await convert(file));
        const bytes = buildPdf(converted);
        const pdf = new File([bytes.buffer as ArrayBuffer], `Scan-${new Date().toISOString().slice(0, 16).replace(/[:T]/g, '-')}.pdf`, {
          type: 'application/pdf',
        });
        uploaded.push(await api.documents.upload(pdf, { idempotencyKey: true }));
      }
      setPages([]);
      setPhase('done');
      onUploaded?.();
      uploaded.forEach((doc) => void watch(doc));
    } catch (error) {
      setProblem(error instanceof Error && !('kind' in error) ? t('capture.failedConvert') : tErrors(toApiError(error).kind));
      setPhase('collecting');
    }
  };

  const again = (): void => {
    setOutcomes([]);
    setPhase('collecting');
  };

  const label = (o: Outcome): string => {
    const names = o.detail.typeNames;
    return names?.[locale] ?? names?.de ?? o.detail.typeCode ?? '';
  };

  return (
    <section aria-labelledby="capture-title" data-testid="capture-panel">
      <h3 id="capture-title">{t('capture.title')}</h3>

      {phase !== 'done' ? (
        <>
          <p>{t('capture.hint')}</p>
          <div style={{ display: 'flex', gap: '0.75rem', flexWrap: 'wrap', alignItems: 'center' }}>
            <MobileCapture onCapture={addPages} label={pages.length === 0 ? t('capture.first') : t('capture.next')} disabled={phase === 'working'} />
            <label>
              <span style={{ display: 'none' }}>{t('capture.gallery')}</span>
              <input
                type="file"
                accept="image/*,application/pdf"
                multiple
                aria-label={t('capture.gallery')}
                disabled={phase === 'working'}
                onChange={(event) => {
                  addPages(Array.from(event.target.files ?? []));
                  event.target.value = '';
                }}
              />
            </label>
          </div>

          {pages.length > 0 ? (
            <>
              <ol aria-label={t('capture.pages')} style={{ display: 'grid', gap: '0.5rem', padding: 0, listStyle: 'none', margin: '0.75rem 0' }}>
                {pages.map((page, index) => (
                  <li key={`${page.name}-${index}`} style={{ display: 'flex', gap: '0.75rem', alignItems: 'center' }}>
                    {previews[index] ? (
                      // eslint-disable-next-line @next/next/no-img-element
                      <img src={previews[index] ?? ''} alt="" width={48} height={64} style={{ objectFit: 'cover', borderRadius: 4 }} />
                    ) : null}
                    <span>{t('capture.page', { number: index + 1 })}</span>
                    <Button variant="ghost" aria-label={t('capture.up', { number: index + 1 })} disabled={index === 0 || phase === 'working'} onClick={() => move(index, -1)}>
                      ↑
                    </Button>
                    <Button
                      variant="ghost"
                      aria-label={t('capture.down', { number: index + 1 })}
                      disabled={index === pages.length - 1 || phase === 'working'}
                      onClick={() => move(index, 1)}
                    >
                      ↓
                    </Button>
                    <Button variant="ghost" aria-label={t('capture.remove', { number: index + 1 })} disabled={phase === 'working'} onClick={() => setPages(pages.filter((_, i) => i !== index))}>
                      ✕
                    </Button>
                  </li>
                ))}
              </ol>
              {pages.length > 1 ? <Checkbox label={t('capture.separate')} checked={separate} onChange={(event) => setSeparate(event.target.checked)} /> : null}
              <Button variant="primary" size="lg" loading={phase === 'working'} onClick={() => void save()}>
                {pages.length > 1 && !separate ? t('capture.saveOne', { count: pages.length }) : t('capture.save', { count: pages.length })}
              </Button>
            </>
          ) : null}
          <div aria-live="polite">{problem ? <p role="alert">{problem}</p> : null}</div>
        </>
      ) : (
        <div aria-live="polite" data-testid="capture-result">
          <p>{t('capture.saved')}</p>
          <ul style={{ padding: 0, listStyle: 'none', display: 'grid', gap: '0.5rem' }}>
            {outcomes.map((o) => (
              <li key={o.id} data-testid="capture-outcome">
                <strong>{o.title}</strong>{' '}
                {!o.settled ? (
                  <span>{t('capture.reading')}</span>
                ) : o.detail.status === 'needs_review' ? (
                  <span>
                    {t('capture.review')} <Link href="/review">{t('capture.toReview')}</Link>
                  </span>
                ) : o.detail.status === 'failed' ? (
                  <span>{t('capture.failed')}</span>
                ) : (
                  <span>{label(o) ? t('capture.recognised', { type: label(o) }) : t('capture.filed')}</span>
                )}{' '}
                <Link href={`/documents/${o.id}`}>{t('capture.open')}</Link>
              </li>
            ))}
          </ul>
          <Button variant="secondary" onClick={again}>
            {tCommon('actions.capture')}
          </Button>
        </div>
      )}
    </section>
  );
}
