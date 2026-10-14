'use client';

import Link from 'next/link';
import { useParams } from 'next/navigation';
import { useState } from 'react';
import { ProcessingPanel } from '@/components/app/processing-panel';
import { PageHeader, PageSection, ResourceView } from '@/components/app/resource-view';
import { Badge, Card, CardBody, CardHeader, EmptyState, StatusPill, Tabs } from '@/components/ui';
import { Button } from '@/components/ui/button';
import { buttonClass } from '@/components/ui/button-class';
import { InlineAlert, SkeletonText } from '@/components/ui/feedback';
import { useToast } from '@/components/ui/toast';
import { formatDate, formatDateTime, formatPercent } from '@/i18n/format';
import { useFormatters, useLocale, useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { toApiError } from '@/lib/api/errors';
import { useApiResource } from '@/lib/api/hooks';
import type { DocumentDetail } from '@/lib/api/types';
import { useStatusLabels } from '@/lib/format/status';
import { downloadBlob } from '@/lib/download';
import styles from './viewer.module.css';

/**
 * Universal document viewer (§5.2.6).
 *
 * Shows the original, the extracted fields with their evidence, metadata,
 * history and relations. Preview is only claimed when the backend reports it;
 * otherwise the download path is offered instead of a broken frame.
 */
export default function DocumentViewerPage() {
  const params = useParams<{ id: string }>();
  const id = typeof params.id === 'string' ? params.id : '';
  const t = useTranslations('documentViewer');
  const tCommon = useTranslations('common');
  const tErrors = useTranslations('errors');
  const tStates = useTranslations('states');
  const locale = useLocale();
  const formatters = useFormatters();
  const statusLabel = useStatusLabels('documents');
  const toast = useToast();
  const [busy, setBusy] = useState(false);
  const [processingKey, setProcessingKey] = useState(0);

  const { state, reload, isRefreshing } = useApiResource<DocumentDetail>(
    (signal) => api.documents.get(id, signal),
    [id],
  );

  const download = async (fileName?: string): Promise<void> => {
    setBusy(true);
    try {
      const blob = await api.documents.downloadOriginal(id);
      downloadBlob(blob, fileName ?? id);
    } catch (error) {
      toast.error(tErrors(toApiError(error).kind));
    } finally {
      setBusy(false);
    }
  };

  const aiRead = async (): Promise<void> => {
    // The pages leave the platform for the AI provider the administrator configured: a person decides, each time.
    if (!window.confirm(t('aiRead.confirm'))) return;
    setBusy(true);
    try {
      const result = await api.documents.aiRead(id);
      if (result.status === 'OK') {
        toast.success(t('aiRead.done', { count: Object.keys(result.found).length }), result.summary ?? undefined);
        setProcessingKey((value) => value + 1);
        reload();
      } else {
        toast.warning(t(`aiRead.status.${result.status}` as 'aiRead.status.FAILED'));
      }
    } catch (error) {
      toast.error(tErrors(toApiError(error).kind));
    } finally {
      setBusy(false);
    }
  };

  const reprocess = async (): Promise<void> => {
    setBusy(true);
    try {
      await api.documents.reprocess(id);
      toast.success(t('action.reprocess'));
      setProcessingKey((value) => value + 1);
      reload();
    } catch (error) {
      toast.error(tErrors(toApiError(error).kind));
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <PageHeader
        title={state.status === 'ready' ? state.data.title : t('tab.preview')}
        subtitle={t('subtitle')}
        breadcrumbs={[
          { label: tCommon('app.name'), href: '/dashboard' },
          { label: t('tab.preview'), href: '/documents' },
          { label: state.status === 'ready' ? state.data.title : id },
        ]}
        actions={
          <>
            <a
              href={`/api/v1/documents/${id}/content`}
              target="_blank"
              rel="noopener noreferrer"
              className={buttonClass({ variant: 'secondary' })}
            >
              {t('action.openInBrowser')}
            </a>
            <Button
              variant="secondary"
              iconLeft="download"
              loading={busy}
              onClick={() => void download(state.status === 'ready' ? state.data.title : undefined)}
            >
              {t('action.download')}
            </Button>
            <Button
              variant="secondary"
              iconLeft="sparkles"
              loading={busy}
              onClick={() => void aiRead()}
            >
              {t('action.aiRead')}
            </Button>
            <Button
              variant="secondary"
              iconLeft="refresh"
              loading={busy}
              onClick={() => void reprocess()}
            >
              {t('action.reprocess')}
            </Button>
          </>
        }
      />

      <ResourceView
        state={state}
        onRetry={reload}
        isRefreshing={isRefreshing}
        skeleton={
          <Card>
            <CardBody>
              <SkeletonText lines={6} />
            </CardBody>
          </Card>
        }
        emptyState={
          <EmptyState
            icon="file-text"
            title={tStates('notFound.title')}
            description={tStates('notFound.description')}
          />
        }
      >
        {(document) => (
          <>
            {document.processingError ? (
              <InlineAlert tone="danger" title={t('processing.failed')}>
                {document.processingError}
              </InlineAlert>
            ) : null}
            {document.status === 'processing' ? (
              <InlineAlert tone="info">{t('processing.inProgress')}</InlineAlert>
            ) : null}

            <ProcessingPanel documentId={id} reloadKey={processingKey} />

            <div className={styles.summaryRow}>
              <StatusPill label={statusLabel(document.status)} status={document.status} />
              {document.documentType ? (
                <Badge tone="neutral" variant="outline">
                  {document.documentType}
                </Badge>
              ) : null}
              {document.legalHold ? (
                <Badge tone="danger" variant="outline">
                  {t('metadata.checksum')}
                </Badge>
              ) : null}
              <span className={styles.summaryMeta}>
                {t('metadata.uploadedBy')}: {document.uploadedBy ?? tCommon('labels.unknown')} ·{' '}
                {formatDateTime(document.receivedAt, locale)}
              </span>
            </div>

            <Tabs
              label={document.title}
              tabs={[
                {
                  id: 'preview',
                  label: t('tab.preview'),
                  content: document.previewAvailable ? (
                    <iframe
                      className={styles.preview}
                      title={document.title}
                      src={`/api/v1/documents/${document.id}/content`}
                    />
                  ) : (
                    <EmptyState
                      icon="file-text"
                      title={t('preview.unavailable')}
                      description={t('preview.downloadHint')}
                      actions={
                        <Button
                          variant="secondary"
                          iconLeft="download"
                          onClick={() => void download(document.title)}
                        >
                          {t('action.download')}
                        </Button>
                      }
                    />
                  ),
                },
                {
                  id: 'fields',
                  label: t('tab.fields'),
                  content:
                    document.fields && document.fields.length > 0 ? (
                      <table className={styles.table}>
                        <caption className={styles.visuallyHidden}>{document.title}</caption>
                        <thead>
                          <tr>
                            <th scope="col">{t('field.label')}</th>
                            <th scope="col">{t('field.value')}</th>
                            <th scope="col">{t('field.confidence')}</th>
                            <th scope="col">{t('field.source')}</th>
                          </tr>
                        </thead>
                        <tbody>
                          {document.fields.map((field) => (
                            <tr key={field.name}>
                              <th scope="row">{field.label}</th>
                              <td>{field.value ?? t('field.noValue')}</td>
                              <td>{formatPercent(field.confidence, locale)}</td>
                              <td>{field.source ?? tCommon('labels.unknown')}</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    ) : (
                      <EmptyState
                        icon="sparkles"
                        title={t('field.noValue')}
                        description={t('processing.inProgress')}
                      />
                    ),
                },
                {
                  id: 'metadata',
                  label: t('tab.metadata'),
                  content: (
                    <dl className={styles.metaList}>
                      <div>
                        <dt>{t('metadata.mimeType')}</dt>
                        <dd>{document.mimeType ?? tCommon('labels.unknown')}</dd>
                      </div>
                      <div>
                        <dt>{t('metadata.size')}</dt>
                        <dd>
                          {typeof document.sizeBytes === 'number'
                            ? formatters.fileSize(document.sizeBytes)
                            : tCommon('labels.unknown')}
                        </dd>
                      </div>
                      <div>
                        <dt>{t('metadata.pages')}</dt>
                        <dd>{document.pageCount ?? tCommon('labels.unknown')}</dd>
                      </div>
                      <div>
                        <dt>{t('metadata.checksum')}</dt>
                        <dd className={styles.checksum}>
                          {document.checksum ?? tCommon('labels.unknown')}
                        </dd>
                      </div>
                      <div>
                        <dt>{tCommon('labels.createdAt')}</dt>
                        <dd>{formatDateTime(document.receivedAt, locale)}</dd>
                      </div>
                      <div>
                        <dt>{tCommon('labels.updatedAt')}</dt>
                        <dd>{formatDateTime(document.receivedAt, locale)}</dd>
                      </div>
                    </dl>
                  ),
                },
                {
                  id: 'history',
                  label: t('tab.history'),
                  content:
                    document.history && document.history.length > 0 ? (
                      <ul className={styles.history}>
                        {document.history.map((entry) => (
                          <li key={entry.id}>
                            <span className={styles.historyTime}>
                              {formatDateTime(entry.at, locale)}
                            </span>
                            <span className={styles.historyAction}>{entry.action}</span>
                            <span className={styles.historyActor}>{entry.actor}</span>
                            <StatusPill label={entry.result} status={entry.result} />
                          </li>
                        ))}
                      </ul>
                    ) : (
                      <EmptyState icon="clock" title={t('history.empty')} />
                    ),
                },
                {
                  id: 'relations',
                  label: t('tab.relations'),
                  content:
                    document.relations && document.relations.length > 0 ? (
                      <ul className={styles.relations}>
                        {document.relations.map((relation) => (
                          <li key={`${relation.kind}-${relation.id}`}>
                            <Badge tone="info" variant="outline">
                              {relation.kind}
                            </Badge>
                            <Link href={`/${relation.kind === 'case' ? 'cases' : relation.kind}`}>
                              {relation.label}
                            </Link>
                          </li>
                        ))}
                      </ul>
                    ) : (
                      <EmptyState icon="folder" title={t('relations.empty')} />
                    ),
                },
              ]}
            />

            <PageSection title={t('tab.metadata')}>
              <Card>
                <CardHeader
                  title={t('metadata.uploadedBy')}
                  description={`${formatDate(document.receivedAt, locale, 'medium')} · ${tCommon('labels.status')}: ${statusLabel(document.status)}`}
                  icon="file-text"
                  headingLevel={3}
                />
                <CardBody>
                  <span className={styles.summaryMeta}>{t('preview.downloadHint')}</span>
                </CardBody>
              </Card>
            </PageSection>
          </>
        )}
      </ResourceView>
    </>
  );
}
