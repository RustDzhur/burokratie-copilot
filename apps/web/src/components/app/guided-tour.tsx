'use client';

import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { useRouter, usePathname } from 'next/navigation';
import { Button } from '@/components/ui/button';
import { useTranslations } from '@/i18n/provider';
import { stepsFor, visible, type TourStep } from '@/lib/tour/steps';

const FALLBACK_STEP: TourStep = { id: 'welcome' };

interface Box {
  readonly top: number;
  readonly left: number;
  readonly width: number;
  readonly height: number;
}

/**
 * A short guided tour over the real screens: each step points at the part of the page it talks about, says what it is for and what
 * the person gets from it. It can be left at any time (Esc or "Skip"), is a dialog for assistive technology, and never changes any
 * data. A stop whose target is not on the screen is shown as a centred card instead of pointing at nothing.
 */
export function GuidedTour({ persona, onClose }: { readonly persona: string; readonly onClose: () => void }) {
  const t = useTranslations('tour');
  const router = useRouter();
  const pathname = usePathname();
  const steps = useMemo<readonly TourStep[]>(() => stepsFor(persona), [persona]);
  const [index, setIndex] = useState(0);
  const [box, setBox] = useState<Box | null>(null);
  const primary = useRef<HTMLButtonElement | null>(null);
  const step: TourStep = steps[index] ?? steps[0] ?? FALLBACK_STEP;

  // Go to the page the step belongs to.
  useEffect(() => {
    if (step.href && pathname !== step.href.split('?')[0]) router.push(step.href);
  }, [step, pathname, router]);

  const locate = useCallback((): void => {
    const element = step.selector ? document.querySelector(step.selector) : null;
    if (visible(element)) {
      element.scrollIntoView({ block: 'center', inline: 'nearest' });
      const rect = element.getBoundingClientRect();
      setBox({ top: rect.top, left: rect.left, width: rect.width, height: rect.height });
    } else {
      setBox(null);
    }
  }, [step]);

  // The target may appear a moment after navigation; look for it for up to two seconds, then fall back to the centred card.
  useLayoutEffect(() => {
    locate();
    let tries = 0;
    const timer = window.setInterval(() => {
      tries += 1;
      locate();
      if (tries >= 20) window.clearInterval(timer);
    }, 100);
    window.addEventListener('resize', locate);
    window.addEventListener('scroll', locate, true);
    return () => {
      window.clearInterval(timer);
      window.removeEventListener('resize', locate);
      window.removeEventListener('scroll', locate, true);
    };
  }, [locate]);

  useEffect(() => {
    primary.current?.focus();
  }, [index]);

  useEffect(() => {
    const onKey = (event: KeyboardEvent): void => {
      if (event.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [onClose]);

  const last = index === steps.length - 1;
  const width = Math.min(380, typeof window === 'undefined' ? 380 : window.innerWidth - 24);
  const tall = box !== null && box.height > window.innerHeight * 0.45;
  const popover: React.CSSProperties = box && tall
    ? { bottom: 16, left: Math.min(Math.max(12, box.left), window.innerWidth - width - 12) }
    : box
    ? {
        top: box.top + box.height + 14 + 220 > window.innerHeight ? Math.max(12, box.top - 14 - 220) : box.top + box.height + 14,
        left: Math.min(Math.max(12, box.left), window.innerWidth - width - 12),
      }
    : { top: '50%', left: '50%', transform: 'translate(-50%, -50%)' };

  return (
    <div>
      {/* Blocks clicks on the page behind the tour; the tour changes nothing. */}
      <div style={{ position: 'fixed', inset: 0, zIndex: 9998, background: box ? 'transparent' : 'rgba(0,0,0,0.55)' }} />
      {box ? (
        <div
          aria-hidden="true"
          style={{
            position: 'fixed', top: box.top - 6, left: box.left - 6, width: box.width + 12, height: box.height + 12, zIndex: 9999,
            borderRadius: 10, boxShadow: '0 0 0 9999px rgba(0,0,0,0.55)', outline: '2px solid var(--color-accent, #2563eb)', pointerEvents: 'none',
          }}
        />
      ) : null}
      <div
        role="dialog"
        aria-modal="false"
        aria-labelledby="tour-title"
        aria-describedby="tour-body"
        style={{
          position: 'fixed', zIndex: 10000, width, background: 'var(--color-surface, #fff)', color: 'var(--color-text, #111)',
          border: '1px solid var(--color-border, #ccc)', borderRadius: 12, padding: '1rem', boxShadow: '0 12px 40px rgba(0,0,0,0.35)', ...popover,
        }}
      >
        <p style={{ margin: 0, fontSize: '0.8rem', opacity: 0.8 }} aria-live="polite">
          {t('progress', { current: index + 1, total: steps.length })}
        </p>
        <h2 id="tour-title" style={{ margin: '0.25rem 0 0.5rem', fontSize: '1.1rem' }}>
          {t(`step.${step.id}.title` as 'step.welcome.title')}
        </h2>
        <p id="tour-body" style={{ margin: '0 0 1rem' }}>
          {t(`step.${step.id}.body` as 'step.welcome.body')}
        </p>
        <div style={{ display: 'flex', gap: '0.5rem', justifyContent: 'space-between', flexWrap: 'wrap' }}>
          <Button variant="ghost" onClick={onClose}>
            {t('skip')}
          </Button>
          <span style={{ display: 'flex', gap: '0.5rem' }}>
            {index > 0 ? (
              <Button variant="secondary" onClick={() => setIndex(index - 1)}>
                {t('back')}
              </Button>
            ) : null}
            <Button ref={primary} onClick={() => (last ? onClose() : setIndex(index + 1))}>
              {last ? t('done') : t('next')}
            </Button>
          </span>
        </div>
      </div>
    </div>
  );
}
