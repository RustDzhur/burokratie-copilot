'use client';

import { useState } from 'react';
import { PageHeader, PageSection, ResourceView } from '@/components/app/resource-view';
import { Card, CardBody, DataTable, EmptyState, StatusPill } from '@/components/ui';
import type { Column } from '@/components/ui';
import { Button } from '@/components/ui/button';
import { InlineAlert, SkeletonCards, SkeletonTable } from '@/components/ui/feedback';
import { useToast } from '@/components/ui/toast';
import { formatCurrency, formatDate } from '@/i18n/format';
import { useLocale, useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { toApiError } from '@/lib/api/errors';
import { useApiResource } from '@/lib/api/hooks';
import type { InvoiceSummary, MemberSummary } from '@/lib/api/types';
import { useGroupedLabels, useStatusLabels } from '@/lib/format/status';
import styles from './company.module.css';

/**
 * Company workspace (§5.2.23).
 *
 * KPIs come from the aggregated dashboard endpoint and approvals from the
 * invoice list — both are real backend data filtered by the caller's
 * permissions; the client never computes financial figures itself.
 */
export default function CompanyPage() {
  const t = useTranslations('company');
  const tNav = useTranslations('nav');
  const tCommon = useTranslations('common');
  const tErrors = useTranslations('errors');
  const locale = useLocale();
  const toast = useToast();
  const statusLabel = useStatusLabels('invoices');
  const roleLabel = useGroupedLabels('employees', 'role');
  const [pendingId, setPendingId] = useState<string | null>(null);

  const summary = useApiResource((signal) => api.dashboard.summary(signal), []);
  const approvals = useApiResource(
    (signal) => api.invoices.list({ status: 'review', size: 25 }, signal),
    [],
    { keepPreviousData: true },
  );
  const team = useApiResource((signal) => api.members.list({ size: 25 }, signal), []);

  const decide = async (invoice: InvoiceSummary, approve: boolean): Promise<void> => {
    setPendingId(invoice.id);
    try {
      if (!approve) {
        toast.warning(t('action.reject'), invoice.number);
        return;
      }
      await api.invoices.approve(invoice.id);
      toast.success(t('action.approve'), invoice.number);
      approvals.reload();
    } catch (error) {
      toast.error(tErrors(toApiError(error).kind));
    } finally {
      setPendingId(null);
    }
  };

  const approvalColumns: readonly Column<InvoiceSummary>[] = [
    { key: 'number', header: t('column.approval'), render: (row) => row.number },
    { key: 'requester', header: t('column.requester'), render: (row) => row.counterparty },
    {
      key: 'amount',
      header: t('column.amount'),
      align: 'end',
      render: (row) => formatCurrency(row.amount, locale, row.currency),
    },
    {
      key: 'dueDate',
      header: t('column.dueDate'),
      render: (row) => (row.dueDate ? formatDate(row.dueDate, locale) : tCommon('labels.unknown')),
    },
    {
      key: 'status',
      header: tCommon('labels.status'),
      render: (row) => <StatusPill label={statusLabel(row.status)} status={row.status} />,
    },
  ];

  const teamColumns: readonly Column<MemberSummary>[] = [
    { key: 'name', header: tCommon('labels.assignee'), render: (row) => row.name },
    { key: 'role', header: t('section.teams'), render: (row) => roleLabel(row.role) },
    {
      key: 'status',
      header: tCommon('labels.status'),
      render: (row) => <StatusPill label={row.status} status={row.status} />,
    },
  ];

  return (
    <>
      <PageHeader title={tNav('items.company')} subtitle={t('subtitle')} />

      <PageSection title={t('permission.notice')}>
        <ResourceView
          state={summary.state}
          onRetry={summary.reload}
          isRefreshing={summary.isRefreshing}
          skeleton={<SkeletonCards count={4} />}
          isEmpty={(data) => Object.keys(data.counts).length === 0}
          emptyState={<EmptyState icon="bar-chart" title={tCommon('labels.none')} />}
        >
          {(data) => (
            <ul className={styles.kpiGrid}>
              {(
                [
                  ['openCases', t('kpi.openCases')],
                  ['pendingApprovals', t('kpi.pendingApprovals')],
                  ['invoicesThisMonth', t('kpi.incomingInvoices')],
                  ['overdueReceivables', t('kpi.overdueReceivables')],
                ] as const
              ).map(([key, label]) => (
                <li key={key}>
                  <Card>
                    <CardBody>
                      <p className={styles.kpiLabel}>{label}</p>
                      <p className={styles.kpiValue}>{data.counts[key] ?? 0}</p>
                    </CardBody>
                  </Card>
                </li>
              ))}
            </ul>
          )}
        </ResourceView>
      </PageSection>

      <PageSection title={t('section.approvals')}>
        <ResourceView
          state={approvals.state}
          onRetry={approvals.reload}
          isRefreshing={approvals.isRefreshing}
          skeleton={<SkeletonTable rows={4} columns={5} />}
          isEmpty={(data) => data.items.length === 0}
          emptyState={
            <EmptyState
              icon="check-circle"
              title={t('empty.title')}
              description={t('empty.description')}
            />
          }
        >
          {(data) => (
            <DataTable
              caption={t('section.approvals')}
              columns={approvalColumns}
              rows={data.items}
              rowKey={(row) => row.id}
              rowActions={(row) => (
                <span className={styles.rowActions}>
                  <Button
                    variant="secondary"
                    size="sm"
                    loading={pendingId === row.id}
                    onClick={() => void decide(row, true)}
                  >
                    {t('action.approve')}
                  </Button>
                  <Button
                    variant="ghost"
                    size="sm"
                    disabled={pendingId === row.id}
                    onClick={() => void decide(row, false)}
                  >
                    {t('action.reject')}
                  </Button>
                </span>
              )}
            />
          )}
        </ResourceView>
      </PageSection>

      <PageSection title={t('section.teams')}>
        <ResourceView
          state={team.state}
          onRetry={team.reload}
          isRefreshing={team.isRefreshing}
          skeleton={<SkeletonTable rows={3} columns={3} />}
          isEmpty={(data) => data.items.length === 0}
          emptyState={
            <EmptyState
              icon="users"
              title={t('empty.title')}
              description={t('empty.description')}
            />
          }
        >
          {(data) => (
            <DataTable
              caption={t('section.teams')}
              columns={teamColumns}
              rows={data.items}
              rowKey={(row) => row.id}
            />
          )}
        </ResourceView>
      </PageSection>

      <InlineAlert tone="info">{t('permission.notice')}</InlineAlert>
    </>
  );
}
