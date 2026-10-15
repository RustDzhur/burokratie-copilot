'use client';

import Link from 'next/link';
import { useParams } from 'next/navigation';
import { RelationshipList } from '@/components/app/relationship-list';
import { PageHeader, PageSection, ResourceView } from '@/components/app/resource-view';
import { Card, CardBody } from '@/components/ui';
import { buttonClass } from '@/components/ui/button-class';
import { SkeletonText } from '@/components/ui/feedback';
import { formatDate, formatDateTime } from '@/i18n/format';
import { useLocale, useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { useApiResource } from '@/lib/api/hooks';

/** One contract: terms, obligations, the documents linked to it, and its timeline. */
export default function ContractPage() {
  const { id } = useParams<{ id: string }>();
  const t = useTranslations('entityMemory');
  const locale = useLocale();
  const { state, reload, isRefreshing } = useApiResource((signal) => api.memory.contract(id, signal), [id]);
  const relationships = useApiResource((signal) => api.memory.contractRelationships(id, signal), [id]);
  const timeline = useApiResource((signal) => api.memory.contractTimeline(id, signal), [id]);

  return (
    <>
      <PageHeader
        title={state.status === 'ready' ? state.data.title : t('back')}
        subtitle={state.status === 'ready' ? [state.data.status, state.data.partnerName].filter(Boolean).join(' · ') : undefined}
        actions={<Link href="/contracts" className={buttonClass({ variant: 'secondary' })}>{t('back')}</Link>}
      />
      <ResourceView
        state={state}
        onRetry={reload}
        isRefreshing={isRefreshing}
        isEmpty={() => false}
        emptyState={null}
        skeleton={<Card><CardBody><SkeletonText lines={5} /></CardBody></Card>}
      >
        {(contract) => (
          <>
            <Card>
              <CardBody>
                <p>{t('period', { from: contract.startDate ? formatDate(contract.startDate, locale) : '—', to: contract.endDate ? formatDate(contract.endDate, locale) : '—' })}</p>
                {contract.cost ? <p>{t('cost')}: {contract.cost}</p> : null}
              </CardBody>
            </Card>
            <PageSection title={t('obligations')}>
              {contract.obligations.length === 0 ? <p>{t('noObligations')}</p> : (
                <ul>{contract.obligations.map((o) => (
                  <li key={o.id}>{o.title} · {o.dueDate ? formatDate(o.dueDate, locale) : '—'} · {o.status}</li>
                ))}</ul>
              )}
            </PageSection>
            <PageSection title={t('relationships')}>
              <RelationshipList items={relationships.state.status === 'ready' ? relationships.state.data : []} onChanged={relationships.reload} />
            </PageSection>
            <PageSection title={t('timeline')}>
              {timeline.state.status === 'ready' && timeline.state.data.length > 0 ? (
                <ul style={{ listStyle: 'none', padding: 0 }}>{timeline.state.data.map((e) => (
                  <li key={`${e.source}-${e.id}`}><strong>{formatDateTime(e.eventTime, locale)}</strong> — {e.title}</li>
                ))}</ul>
              ) : <p>{t('noTimeline')}</p>}
            </PageSection>
          </>
        )}
      </ResourceView>
    </>
  );
}
