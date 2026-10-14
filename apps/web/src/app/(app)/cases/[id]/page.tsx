'use client';

import Link from 'next/link';
import { useParams, useRouter } from 'next/navigation';
import { useState } from 'react';
import { PageHeader, PageSection, ResourceView } from '@/components/app/resource-view';
import { Badge, Card, CardBody, DataTable, EmptyState, StatusPill } from '@/components/ui';
import type { Column } from '@/components/ui';
import { Button } from '@/components/ui/button';
import { buttonClass } from '@/components/ui/button-class';
import { SkeletonText } from '@/components/ui/feedback';
import { useToast } from '@/components/ui/toast';
import { formatDate, formatDateTime } from '@/i18n/format';
import { useLocale, useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { toApiError } from '@/lib/api/errors';
import { useApiResource } from '@/lib/api/hooks';
import type { CaseDetail } from '@/lib/api/types';

/** One case: what it is about, which documents belong to it (each one opens), what happened, what is still missing. */
export default function CaseDetailPage() {
  const { id } = useParams<{ id: string }>();
  const t = useTranslations('cases');
  const tCommon = useTranslations('common');
  const tErrors = useTranslations('errors');
  const locale = useLocale();
  const toast = useToast();
  const router = useRouter();
  const [busy, setBusy] = useState(false);
  const { state, reload, isRefreshing } = useApiResource((signal) => api.cases.detail(id, signal), [id]);

  const remove = async (): Promise<void> => {
    if (!window.confirm(t('detail.deleteConfirm'))) return;
    setBusy(true);
    try {
      await api.deletion.remove('CASE', [id]);
      toast.success(tCommon('bulk.doneOne'));
      router.push('/cases');
    } catch (error) {
      toast.error(tErrors(toApiError(error).kind));
      setBusy(false);
    }
  };

  const documentColumns: readonly Column<CaseDetail['documents'][number]>[] = [
    {
      key: 'filename',
      header: t('detail.document'),
      link: (row) => `/documents/${row.documentId}`,
      render: (row) => row.filename,
    },
    { key: 'status', header: t('column.status'), render: (row) => <StatusPill label={row.processingStatus} status={row.processingStatus.toLowerCase()} /> },
    { key: 'primary', header: '', render: (row) => (row.primary ? <Badge tone="info">{t('detail.primary')}</Badge> : null) },
  ];

  return (
    <>
      <PageHeader
        title={state.status === 'ready' ? state.data.title : t('detail.title')}
        subtitle={state.status === 'ready' ? `${state.data.reference}${state.data.typeName ? ` · ${state.data.typeName}` : ''}` : undefined}
        actions={
          <>
            <Link href="/cases" className={buttonClass({ variant: 'secondary' })}>
              {t('detail.back')}
            </Link>
            <Button variant="danger" iconLeft="trash" loading={busy} onClick={() => void remove()}>
              {tCommon('actions.delete')}
            </Button>
          </>
        }
      />
      <ResourceView
        state={state}
        onRetry={reload}
        isRefreshing={isRefreshing}
        isEmpty={() => false}
        emptyState={null}
        skeleton={
          <Card>
            <CardBody>
              <SkeletonText lines={6} />
            </CardBody>
          </Card>
        }
      >
        {(item) => (
          <>
            <Card>
              <CardBody>
                <dl style={{ display: 'grid', gridTemplateColumns: 'max-content 1fr', gap: '0.25rem 1rem', margin: 0 }}>
                  <dt>{t('column.status')}</dt>
                  <dd style={{ margin: 0 }}>{item.status}</dd>
                  <dt>{t('detail.priority')}</dt>
                  <dd style={{ margin: 0 }}>{item.priority ?? '—'}</dd>
                  <dt>{t('column.dueDate')}</dt>
                  <dd style={{ margin: 0 }}>{item.dueAt ? formatDate(item.dueAt, locale) : '—'}</dd>
                  <dt>{t('detail.opened')}</dt>
                  <dd style={{ margin: 0 }}>{item.openedAt ? formatDate(item.openedAt, locale) : '—'}</dd>
                  <dt>{t('detail.responsible')}</dt>
                  <dd style={{ margin: 0 }}>{item.responsibleName ?? '—'}</dd>
                </dl>
                {item.description ? <p style={{ whiteSpace: 'pre-wrap' }}>{item.description}</p> : null}
              </CardBody>
            </Card>

            <PageSection title={t('detail.documents')}>
              {item.documents.length === 0 ? (
                <EmptyState icon="folder" title={t('detail.noDocuments')} description={t('detail.noDocumentsHint')} />
              ) : (
                <DataTable caption={t('detail.documents')} columns={documentColumns} rows={item.documents} rowKey={(row) => row.documentId} />
              )}
            </PageSection>

            {item.requiredDocuments.length > 0 ? (
              <PageSection title={t('detail.required')}>
                <ul>
                  {item.requiredDocuments.map((name) => (
                    <li key={name}>{name}</li>
                  ))}
                </ul>
              </PageSection>
            ) : null}

            <PageSection title={t('detail.timeline')}>
              {item.timeline.length === 0 ? (
                <p>{t('detail.noTimeline')}</p>
              ) : (
                <ul style={{ listStyle: 'none', padding: 0, display: 'grid', gap: '0.5rem' }}>
                  {item.timeline.map((event) => (
                    <li key={event.id}>
                      <strong>{formatDateTime(event.occurredAt, locale)}</strong> — {event.summary ?? event.type}
                    </li>
                  ))}
                </ul>
              )}
            </PageSection>
          </>
        )}
      </ResourceView>
    </>
  );
}
