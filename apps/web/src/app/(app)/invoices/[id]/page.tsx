'use client';

import Link from 'next/link';
import { useParams } from 'next/navigation';
import { RelationshipList } from '@/components/app/relationship-list';
import { PageHeader, PageSection, ResourceView } from '@/components/app/resource-view';
import { Card, CardBody, InlineAlert } from '@/components/ui';
import { buttonClass } from '@/components/ui/button-class';
import { SkeletonText } from '@/components/ui/feedback';
import { formatDate } from '@/i18n/format';
import { useLocale, useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { useApiResource } from '@/lib/api/hooks';

/** One invoice: amount, sender, the check notes of the platform, and the documents linked to it. */
export default function InvoicePage() {
  const { id } = useParams<{ id: string }>();
  const t = useTranslations('entityMemory');
  const locale = useLocale();
  const { state, reload, isRefreshing } = useApiResource((signal) => api.memory.invoice(id, signal), [id]);
  const documentId = state.status === 'ready' ? state.data.documentId : null;
  const relationships = useApiResource(
    (signal) => (documentId ? api.memory.documentRelationships(documentId, signal) : Promise.resolve([])),
    [documentId],
  );

  return (
    <>
      <PageHeader
        title={state.status === 'ready' ? (state.data.number ?? t('back')) : t('back')}
        subtitle={state.status === 'ready' ? [state.data.status, state.data.supplierName].filter(Boolean).join(' · ') : undefined}
        actions={<Link href="/invoices" className={buttonClass({ variant: 'secondary' })}>{t('back')}</Link>}
      />
      <ResourceView
        state={state}
        onRetry={reload}
        isRefreshing={isRefreshing}
        isEmpty={() => false}
        emptyState={null}
        skeleton={<Card><CardBody><SkeletonText lines={4} /></CardBody></Card>}
      >
        {(invoice) => (
          <>
            <Card>
              <CardBody>
                <p>{t('amount')}: {invoice.gross ?? '—'} {invoice.currency}</p>
                {invoice.dueDate ? <p>{t('due', { date: formatDate(invoice.dueDate, locale) })}</p> : null}
                {invoice.bankDetailsChanged ? <InlineAlert tone="warning">{t('bankChanged')}</InlineAlert> : null}
                {invoice.documentId ? <p><Link href={`/documents/${invoice.documentId}`}>{t('openDocument')}</Link></p> : null}
              </CardBody>
            </Card>
            {invoice.flags.length > 0 ? (
              <PageSection title={t('flags')}>
                <ul>{invoice.flags.map((flag) => <li key={flag}>{flag}</li>)}</ul>
              </PageSection>
            ) : null}
            <PageSection title={t('relationships')}>
              <RelationshipList items={relationships.state.status === 'ready' ? relationships.state.data : []} onChanged={relationships.reload} />
            </PageSection>
          </>
        )}
      </ResourceView>
    </>
  );
}
