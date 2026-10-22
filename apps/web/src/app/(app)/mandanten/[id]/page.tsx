'use client';

import Link from 'next/link';
import { useParams } from 'next/navigation';
import { useMemo } from 'react';
import { PageHeader, PageSection, ResourceView } from '@/components/app/resource-view';
import { Badge, Card, CardBody, DataTable, type Column } from '@/components/ui';
import { buttonClass } from '@/components/ui/button-class';
import { SkeletonText } from '@/components/ui/feedback';
import { formatDate, formatNumber } from '@/i18n/format';
import { useLocale, useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { useApiResource } from '@/lib/api/hooks';
import type {
  BelegStatusValue,
  MandantDeadline,
  MandantDocument,
  MandantHealth,
} from '@/lib/api/types';
import styles from '../mandanten.module.css';

const HEALTH_TONE: Readonly<Record<MandantHealth, 'success' | 'warning' | 'danger'>> = {
  OK: 'success',
  ACTION: 'warning',
  RISK: 'danger',
};

/** The same colour language as the voucher queue (§5.1), so two screens do not teach two meanings. */
const BELEG_TONE: Readonly<Record<BelegStatusValue, 'neutral' | 'warning' | 'success' | 'danger'>> = {
  NEU: 'neutral',
  OFFEN: 'neutral',
  ZU_PRUEFEN: 'warning',
  EXPORTIERT: 'success',
  FEHLER: 'danger',
};

/**
 * One client of the Kanzlei (Kanzlei edition, §5.5).
 *
 * <p>The card exists so the office does not have to leave it to act: the newest vouchers and the deadlines
 * that are still ahead are here, and the button opens the voucher list filtered to this client. Every number
 * comes from the server's aggregation - the card never recounts, because a second count is a second truth.</p>
 *
 * <p>An overdue deadline is deliberately absent from the deadline list and present in the client's health
 * instead: the list is "what is still ahead", and a passed deadline is not a future appointment. Hiding it
 * without the health badge would be the real defect.</p>
 */
export default function MandantPage() {
  const { id } = useParams<{ id: string }>();
  const t = useTranslations('mandanten');
  const td = useTranslations('documents');
  const locale = useLocale();

  const { state, reload, isRefreshing } = useApiResource(
    (signal) => api.mandanten.get(id, signal),
    [id],
  );

  const documentColumns: readonly Column<MandantDocument>[] = useMemo(
    () => [
      {
        key: 'filename',
        header: t('card.documents.filename'),
        link: (row) => `/documents/${row.id}`,
        render: (row) => <span className={styles.filename}>{row.filename}</span>,
      },
      {
        key: 'status',
        header: t('card.documents.status'),
        render: (row) => (
          <Badge tone={BELEG_TONE[row.belegStatus]}>
            {td(`beleg.${row.belegStatus}` as 'beleg.NEU')}
          </Badge>
        ),
      },
      {
        key: 'date',
        header: t('card.documents.date'),
        render: (row) => formatDate(row.date, locale),
      },
    ],
    [t, td, locale],
  );

  const deadlineColumns: readonly Column<MandantDeadline>[] = useMemo(
    () => [
      {
        key: 'type',
        header: t('column.deadlines'),
        render: (row) => t(`deadline.${row.deadlineType}` as 'deadline.DOCUMENT_DATE'),
      },
      {
        key: 'dueDate',
        header: t('card.documents.date'),
        render: (row) => formatDate(row.dueDate, locale),
      },
      {
        key: 'verification',
        header: t('card.documents.status'),
        render: (row) =>
          row.requiresVerification ? (
            <Badge tone="warning">{t('card.deadlines.requiresVerification')}</Badge>
          ) : (
            '—'
          ),
      },
      {
        key: 'document',
        header: t('card.deadlines.document'),
        render: (row) =>
          row.documentId ? (
            <Link href={`/documents/${row.documentId}`}>{t('card.deadlines.document')}</Link>
          ) : (
            '—'
          ),
      },
    ],
    [t, locale],
  );

  return (
    <>
      <PageHeader
        title={state.status === 'ready' ? state.data.displayName : t('card.back')}
        subtitle={t('card.subtitle')}
        meta={
          state.status === 'ready' ? (
            <span className={styles.healthRow}>
              <Badge tone={HEALTH_TONE[state.data.health]}>
                {t(`health.${state.data.health}` as 'health.OK')}
              </Badge>
              <span className={styles.healthHint}>
                {t(`health.${state.data.health}.hint` as 'health.OK.hint')}
              </span>
            </span>
          ) : undefined
        }
        actions={
          <div className={styles.actions}>
            {/* The client filter exists on the voucher list, so the button hands the client over with the
                link instead of making the person pick the same client again. */}
            <Link
              href={`/documents?party=${encodeURIComponent(id)}`}
              className={buttonClass({ variant: 'primary' })}
            >
              {t('card.documents.show')}
            </Link>
            <Link href="/mandanten" className={buttonClass({ variant: 'secondary' })}>
              {t('card.back')}
            </Link>
          </div>
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
        {(mandant) => (
          <>
            <dl className={styles.stats}>
              <div className={styles.stat}>
                <dt>{t('card.stats.documents')}</dt>
                <dd className={styles.statValue}>{formatNumber(mandant.documentsTotal, locale)}</dd>
              </div>
              <div className={styles.stat}>
                <dt>{t('card.stats.offen')}</dt>
                <dd className={styles.statValue}>{formatNumber(mandant.offen, locale)}</dd>
              </div>
              <div className={styles.stat}>
                <dt>{t('card.stats.zuPruefen')}</dt>
                <dd className={styles.statValue}>{formatNumber(mandant.zuPruefen, locale)}</dd>
              </div>
              <div className={styles.stat}>
                <dt>{t('card.stats.exportiert')}</dt>
                <dd className={styles.statValue}>{formatNumber(mandant.exportiert, locale)}</dd>
              </div>
              <div className={styles.stat}>
                <dt>{t('card.stats.fehler')}</dt>
                <dd className={styles.statValue}>{formatNumber(mandant.fehler, locale)}</dd>
              </div>
              <div className={styles.stat}>
                <dt>{t('card.stats.deadlines')}</dt>
                <dd className={styles.statValue}>{formatNumber(mandant.deadlinesOpen, locale)}</dd>
              </div>
              <div className={styles.stat}>
                <dt>{t('card.stats.lastActivity')}</dt>
                <dd className={styles.statValue}>
                  {mandant.lastActivityAt ? formatDate(mandant.lastActivityAt, locale) : '—'}
                </dd>
              </div>
            </dl>

            <PageSection
              title={t('card.documents.title')}
              description={t('card.documents.description')}
            >
              {mandant.lastDocuments.length === 0 ? (
                <p className={styles.hint}>{t('card.documents.empty')}</p>
              ) : (
                <DataTable
                  caption={t('card.documents.title')}
                  columns={documentColumns}
                  rows={mandant.lastDocuments}
                  rowKey={(row) => row.id}
                  dense
                />
              )}
            </PageSection>

            <PageSection
              title={t('card.deadlines.title')}
              description={t('card.deadlines.description')}
            >
              {mandant.openDeadlines.length === 0 ? (
                <p className={styles.hint}>{t('card.deadlines.empty')}</p>
              ) : (
                <DataTable
                  caption={t('card.deadlines.title')}
                  columns={deadlineColumns}
                  rows={mandant.openDeadlines}
                  rowKey={(row) => row.id}
                  dense
                />
              )}
            </PageSection>
          </>
        )}
      </ResourceView>
    </>
  );
}
