'use client';

import { PageSection } from '@/components/app/resource-view';
import { Card, CardBody } from '@/components/ui';
import { formatCurrency, formatDate } from '@/i18n/format';
import { useLocale, useTranslations } from '@/i18n/provider';
import { DeadlineBadge } from './deadline-badge';

export interface BelegdatenCardProps {
  /** The extracted amount; null when the extraction found none. */
  readonly amount?: number | null;
  /** The currency read next to the amount; null when the document names none. */
  readonly amountCurrency?: string | null;
  readonly documentDate?: string | null;
  readonly supplierName?: string | null;
  readonly deadlineDate?: string | null;
  readonly deadlineDaysLeft?: number | null;
}

/** What an empty cell says: "not extracted", not "0,00 €" and not a guessed supplier. */
const NONE = '—';

/**
 * The booking figures of a voucher (Kanzlei edition, §5.1): Betrag, Belegdatum, Lieferant and Frist.
 *
 * <p>Each row shows what the pipeline already extracted, or a dash. Nothing is derived from the file
 * name or the sender here: a card that fills a missing supplier with the e-mail sender would look
 * complete and be wrong. The deadline keeps its badge, so the card and the queue row agree on urgency.</p>
 */
export function BelegdatenCard({
  amount,
  amountCurrency,
  documentDate,
  supplierName,
  deadlineDate,
  deadlineDaysLeft,
}: BelegdatenCardProps) {
  const t = useTranslations('documentViewer');
  const locale = useLocale();
  // The API tells the truth about a missing currency (null); the office writes an amount without one
  // as euro, exactly as the DATEV export already does with an unknown currency.
  const currency = amountCurrency ?? 'EUR';
  return (
    <PageSection title={t('belegdaten.title')}>
      <Card>
        <CardBody>
          <dl
            style={{
              display: 'grid',
              gridTemplateColumns: 'max-content 1fr',
              gap: '0.4rem 1rem',
              margin: 0,
            }}
          >
            <dt>{t('belegdaten.amount')}</dt>
            <dd style={{ margin: 0 }}>
              {amount === null || amount === undefined ? NONE : formatCurrency(amount, locale, currency)}
            </dd>
            <dt>{t('belegdaten.date')}</dt>
            <dd style={{ margin: 0 }}>{documentDate ? formatDate(documentDate, locale) : NONE}</dd>
            <dt>{t('belegdaten.supplier')}</dt>
            <dd style={{ margin: 0 }}>{supplierName || NONE}</dd>
            <dt>{t('belegdaten.deadline')}</dt>
            <dd style={{ margin: 0 }}>
              {deadlineDate ? (
                <span style={{ display: 'inline-flex', alignItems: 'center', gap: '0.5rem', flexWrap: 'wrap' }}>
                  {formatDate(deadlineDate, locale)}
                  <DeadlineBadge date={deadlineDate} daysLeft={deadlineDaysLeft} />
                </span>
              ) : (
                NONE
              )}
            </dd>
          </dl>
        </CardBody>
      </Card>
    </PageSection>
  );
}
