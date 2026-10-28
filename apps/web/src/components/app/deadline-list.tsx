'use client';

import Link from 'next/link';
import { Badge } from '@/components/ui';
import { formatDate } from '@/i18n/format';
import { useLocale, useTranslations } from '@/i18n/provider';
import type { CaseDeadline } from '@/lib/api/types';

/**
 * Deadlines with their origin. A date nobody confirmed is shown as unconfirmed and says why it must be checked: the
 * platform read it from a document or assumed it; it never presents that as a verified legal deadline.
 */
export function DeadlineList({ deadlines }: { readonly deadlines: readonly CaseDeadline[] }) {
  const t = useTranslations('cases');
  const locale = useLocale();
  return (
    <ul style={{ listStyle: 'none', padding: 0, display: 'grid', gap: '0.75rem' }}>
      {deadlines.map((deadline) => {
        const confirmed = deadline.confirmation === 'CONFIRMED';
        return (
          <li key={deadline.id} data-testid="deadline">
            <strong>{deadline.dueDate ? formatDate(deadline.dueDate, locale) : '—'}</strong>{' '}
            <Badge tone={confirmed ? 'success' : 'warning'}>
              {confirmed ? t('detail.deadline.confirmed') : t('detail.deadline.unconfirmed')}
            </Badge>
            <div>
              {t(`detail.deadline.source.${deadline.source}` as 'detail.deadline.source.DOCUMENT')}
              {deadline.sourcePage ? `, ${t('detail.deadline.page', { page: deadline.sourcePage })}` : ''}
              {deadline.ruleVersion ? `, ${t('detail.deadline.rule', { version: deadline.ruleVersion })}` : ''}
              {deadline.sourceDocumentId ? (
                <>
                  {' · '}
                  <Link href={`/documents/${deadline.sourceDocumentId}`}>{t('detail.deadline.openDocument')}</Link>
                </>
              ) : null}
            </div>
            {deadline.sourceSnippet ? <blockquote style={{ margin: '0.25rem 0 0' }}>{deadline.sourceSnippet}</blockquote> : null}
            {confirmed ? null : <small>{t('detail.deadline.unconfirmedHint')}</small>}
          </li>
        );
      })}
    </ul>
  );
}
