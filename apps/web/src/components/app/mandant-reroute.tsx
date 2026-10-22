'use client';

import { useState } from 'react';
import { Button } from '@/components/ui/button';
import { InlineAlert } from '@/components/ui/feedback';
import { useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { toApiError } from '@/lib/api/errors';
import type { MandantRoutingResult } from '@/lib/api/types';
import styles from './mandant-reroute.module.css';

/**
 * "Mandant neu bestimmen" on the document card (§5.2).
 *
 * <p>The button runs the same calculation the processing pipeline runs; a second, screen-only opinion would be
 * worse than none. The answer is shown in words, not only as a toast: "found X with 95 %" is good news, but
 * "two clients match equally well" and "the signals are not enough" are the states a person has to understand
 * before trusting the queue. An existing assignment is never changed - the result then says so.</p>
 */
export function MandantReroute({
  documentId,
  onRouted,
}: {
  readonly documentId: string;
  /** Called after an assignment was made, so the card can show the new client. */
  readonly onRouted?: () => void;
}) {
  const t = useTranslations('documentViewer');
  const tErrors = useTranslations('errors');
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<MandantRoutingResult | null>(null);
  const [error, setError] = useState<string | null>(null);

  const determine = async (): Promise<void> => {
    setBusy(true);
    setError(null);
    try {
      const routing = await api.documents.rerouteMandant(documentId);
      setResult(routing);
      if (routing.status === 'ASSIGNED') onRouted?.();
    } catch (failure) {
      setError(tErrors(toApiError(failure).kind));
    } finally {
      setBusy(false);
    }
  };

  /** The sentence and the colour for one result; every status has its own answer. */
  const message = (routing: MandantRoutingResult): {
    readonly tone: 'success' | 'info' | 'warning' | 'neutral';
    readonly title: string;
    readonly text: string;
  } => {
    const name = routing.mandantName ?? routing.mandantId ?? '';
    const confidence = Math.round((routing.confidence ?? 0) * 100);
    switch (routing.status) {
      case 'ASSIGNED':
        return {
          tone: 'success',
          title: t('routing.assignedTitle'),
          text: t('routing.assigned', { name, confidence }),
        };
      case 'ALREADY_ASSIGNED':
        return {
          tone: 'info',
          title: t('routing.alreadyTitle'),
          text: t('routing.already', { name }),
        };
      case 'AMBIGUOUS': {
        const names = routing.candidates
          .map((candidate) => candidate.mandantName ?? candidate.mandantId)
          .filter(Boolean)
          .join(', ');
        return {
          tone: 'warning',
          title: t('routing.ambiguousTitle'),
          text: t('routing.ambiguous', { names }),
        };
      }
      case 'BELOW_THRESHOLD': {
        const best = routing.candidates[0];
        return {
          tone: 'warning',
          title: t('routing.belowTitle'),
          text: t('routing.below', {
            name: best?.mandantName ?? best?.mandantId ?? '—',
            confidence: Math.round((best?.confidence ?? 0) * 100),
          }),
        };
      }
      case 'NO_SIGNALS':
      default:
        return {
          tone: 'neutral',
          title: t('routing.noneTitle'),
          text: t('routing.none'),
        };
    }
  };

  const shown = result ? message(result) : null;

  return (
    <div className={styles.wrapper} data-testid="mandant-reroute">
      <Button variant="secondary" iconLeft="search" loading={busy} onClick={() => void determine()}>
        {t('routing.action')}
      </Button>
      {shown ? (
        <div className={styles.result}>
          <InlineAlert tone={shown.tone} title={shown.title}>
            {shown.text}
          </InlineAlert>
        </div>
      ) : null}
      {error ? (
        <div className={styles.result}>
          <InlineAlert tone="danger" title={t('routing.failed')}>
            {error}
          </InlineAlert>
        </div>
      ) : null}
    </div>
  );
}
