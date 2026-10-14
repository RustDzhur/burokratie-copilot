'use client';

import Link from 'next/link';
import { useMemo, useState } from 'react';
import { ProcessingPanel } from '@/components/app/processing-panel';
import { PageHeader, PageSection, ResourceView } from '@/components/app/resource-view';
import { Badge, Card, CardBody, CardHeader, EmptyState, StatusPill } from '@/components/ui';
import { Button } from '@/components/ui/button';
import { InlineAlert, SkeletonTable } from '@/components/ui/feedback';
import { useToast } from '@/components/ui/toast';
import { formatDate, formatPercent } from '@/i18n/format';
import { useLocale, useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { toApiError } from '@/lib/api/errors';
import { useApiResource } from '@/lib/api/hooks';
import type { ExtractedField, ReviewItem } from '@/lib/api/types';
import styles from './review.module.css';

function confidenceTone(confidence: number): 'success' | 'warning' | 'danger' {
  if (confidence >= 0.85) return 'success';
  if (confidence >= 0.6) return 'warning';
  return 'danger';
}

/**
 * Review centre (§5.2.7): extracted values are shown with their confidence and
 * their evidence inside the document. Nothing is accepted automatically — the
 * user confirms, and only the backend response is treated as success.
 */
export default function ReviewPage() {
  const t = useTranslations('review');
  const tNav = useTranslations('nav');
  const tViewer = useTranslations('documentViewer');
  const tCommon = useTranslations('common');
  const tStates = useTranslations('states');
  const tErrors = useTranslations('errors');
  const locale = useLocale();
  const toast = useToast();

  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  const { state, reload, isRefreshing } = useApiResource(
    (signal) => api.review.queue({ size: 50 }, signal),
    [],
    { keepPreviousData: true },
  );

  const items = useMemo(() => (state.status === 'ready' ? state.data.items : []), [state]);
  const selected: ReviewItem | undefined = useMemo(
    () => items.find((item) => item.documentId === (selectedId ?? items[0]?.documentId)),
    [items, selectedId],
  );

  const confirm = async (): Promise<void> => {
    if (!selected) return;
    setPending(true);
    try {
      await api.review.confirm(selected.documentId, {
        fields: selected.fields.map((field: ExtractedField) => ({
          name: field.name,
          value: field.value ?? '',
        })),
      });
      toast.success(t('saved'));
      reload();
    } catch (error) {
      toast.error(tErrors(toApiError(error).kind));
    } finally {
      setPending(false);
    }
  };

  const reject = async (): Promise<void> => {
    if (!selected) return;
    setPending(true);
    try {
      await api.review.reject(selected.documentId);
      toast.warning(t('action.reject'));
      reload();
    } catch (error) {
      toast.error(tErrors(toApiError(error).kind));
    } finally {
      setPending(false);
    }
  };

  return (
    <>
      <PageHeader
        title={tNav('items.review')}
        subtitle={t('subtitle')}
        meta={
          state.status === 'ready' ? t('queue.count', { count: state.data.totalItems }) : undefined
        }
        actions={
          <Button variant="secondary" iconLeft="refresh" onClick={reload} loading={isRefreshing}>
            {tCommon('actions.refresh')}
          </Button>
        }
      />

      <ResourceView
        state={state}
        onRetry={reload}
        isRefreshing={isRefreshing}
        skeleton={<SkeletonTable rows={5} columns={3} />}
        isEmpty={(data) => data.items.length === 0}
        emptyState={
          <EmptyState
            icon="check-circle"
            title={t('empty.title')}
            description={t('empty.description')}
            actions={
              <Button variant="secondary" onClick={reload} iconLeft="refresh">
                {tCommon('actions.refresh')}
              </Button>
            }
          />
        }
      >
        {(data) => (
          <div className={styles.layout}>
            <PageSection title={t('queue.title')} className={styles.queue}>
              <ul className={styles.queueList}>
                {data.items.map((item) => {
                  const active = item.documentId === selected?.documentId;
                  return (
                    <li key={item.documentId}>
                      <button
                        type="button"
                        className={[styles.queueItem, active ? styles.queueItemActive : '']
                          .filter(Boolean)
                          .join(' ')}
                        aria-current={active ? 'true' : undefined}
                        onClick={() => setSelectedId(item.documentId)}
                      >
                        <span className={styles.queueTitle}>{item.title}</span>
                        <span className={styles.queueMeta}>
                          {item.senderName ?? tCommon('labels.unknown')} ·{' '}
                          {formatDate(item.receivedAt, locale, 'medium')}
                        </span>
                      </button>
                    </li>
                  );
                })}
              </ul>
            </PageSection>

            <PageSection title={selected?.title ?? t('queue.title')} className={styles.detail}>
              {selected ? (
                <>
                  {selected.possibleDuplicateOf ? (
                    <InlineAlert tone="warning" title={t('duplicate.title')}>
                      {t('duplicate.description')}
                    </InlineAlert>
                  ) : null}

                  <p>
                    <Link href={`/documents/${selected.documentId}`}>{t('openDocument')}</Link>
                  </p>

                  <ProcessingPanel documentId={selected.documentId} />

                  <Card padding="none">
                    <CardHeader
                      title={t('column.field')}
                      description={t('filter.minConfidence')}
                      icon="check-square"
                      headingLevel={3}
                    />
                    <CardBody>
                      <table className={styles.fieldTable}>
                        <caption className={styles.visuallyHidden}>{t('queue.title')}</caption>
                        <thead>
                          <tr>
                            <th scope="col">{t('column.field')}</th>
                            <th scope="col">{t('column.extracted')}</th>
                            <th scope="col">{t('column.confidence')}</th>
                            <th scope="col">{t('column.source')}</th>
                          </tr>
                        </thead>
                        <tbody>
                          {selected.fields.map((field) => (
                            <tr key={field.name}>
                              <th scope="row">{field.label}</th>
                              <td>{field.value ?? <em>{tViewer('field.noValue')}</em>}</td>
                              <td>
                                <StatusPill
                                  label={formatPercent(field.confidence, locale)}
                                  tone={confidenceTone(field.confidence)}
                                />
                              </td>
                              <td>{field.source ?? tCommon('labels.unknown')}</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </CardBody>
                  </Card>

                  <div className={styles.actions}>
                    <Button
                      variant="secondary"
                      onClick={() => void reject()}
                      disabled={pending}
                      iconLeft="x"
                    >
                      {t('action.reject')}
                    </Button>
                    <Button
                      variant="primary"
                      onClick={() => void confirm()}
                      loading={pending}
                      iconLeft="check"
                    >
                      {t('action.confirm')}
                    </Button>
                  </div>
                  <p className={styles.hint}>{tStates('success.saved')}</p>
                  <Badge tone="info" variant="outline">
                    {t('confidence.high')} ≥ 85 %
                  </Badge>
                </>
              ) : (
                <EmptyState
                  icon="inbox"
                  title={t('empty.title')}
                  description={t('empty.description')}
                />
              )}
            </PageSection>
          </div>
        )}
      </ResourceView>
    </>
  );
}
