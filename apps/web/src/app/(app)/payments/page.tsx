'use client';
import { ListPage } from '@/components/app/list-page';
import { StatusPill } from '@/components/ui';
import type { Column } from '@/components/ui';
import { formatCurrency, formatDate } from '@/i18n/format';
import { useLocale, useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import type { PaymentSummary } from '@/lib/api/types';
import { useStatusLabels } from '@/lib/format/status';
import styles from '@/styles/list-cells.module.css';

const STATUS_VALUES = ['unmatched', 'matched', 'suggested', 'ignored'] as const;

export default function PaymentsPage() {
  const t = useTranslations('payments');
  const tCommon = useTranslations('common');
  const locale = useLocale();
  const statusLabel = useStatusLabels('payments');

  const columns: readonly Column<PaymentSummary>[] = [
    {
      key: 'date',
      header: t('column.date'),
      sortable: true,
      render: (row) => formatDate(row.bookingDate, locale),
    },
    { key: 'counterparty', header: t('column.counterparty'), render: (row) => row.counterparty },
    {
      key: 'amount',
      header: t('column.amount'),
      align: 'end',
      render: (row) => formatCurrency(row.amount, locale, row.currency),
    },
    {
      key: 'reference',
      header: t('column.reference'),
      render: (row) => row.reference ?? tCommon('labels.none'),
    },
    {
      key: 'status',
      header: t('column.status'),
      render: (row) => (
        <span className={styles.inline}>
          <StatusPill label={statusLabel(row.status)} status={row.status} />
          {row.suggestion ? <span className={styles.note}>{t('suggestion.notice')}</span> : null}
        </span>
      ),
    },
  ];

  return (
    <ListPage<PaymentSummary>
      screenId="payments"
      namespace="payments"
      load={(params, signal) => api.payments.list(params, signal)}
      columns={columns}
      sortableKeys={['date', 'amount']}
      filters={[
        {
          key: 'status',
          label: t('filter.status'),
          options: STATUS_VALUES.map((value) => ({ value, label: statusLabel(value) })),
        },
      ]}
    />
  );
}
