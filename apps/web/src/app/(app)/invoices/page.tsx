'use client';
import { ListPage } from '@/components/app/list-page';
import { Badge, StatusPill } from '@/components/ui';
import type { Column } from '@/components/ui';
import { InlineAlert } from '@/components/ui/feedback';
import { formatCurrency, formatDate } from '@/i18n/format';
import { useLocale, useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import type { InvoiceSummary } from '@/lib/api/types';
import { useGroupedLabels, useStatusLabels } from '@/lib/format/status';
import styles from '@/styles/list-cells.module.css';

const STATUS_VALUES = ['draft', 'review', 'approved', 'paid', 'overdue'] as const;
const DIRECTION_VALUES = ['incoming', 'outgoing'] as const;

export default function InvoicesPage() {
  const t = useTranslations('invoices');
  const tCommon = useTranslations('common');
  const locale = useLocale();
  const statusLabel = useStatusLabels('invoices');
  const directionLabel = useGroupedLabels('invoices', 'direction');

  const columns: readonly Column<InvoiceSummary>[] = [
    { key: 'number', header: t('column.number'), sortable: true, link: (row) => `/invoices/${row.id}`, render: (row) => row.number },
    { key: 'counterparty', header: t('column.counterparty'), render: (row) => row.counterparty },
    {
      key: 'amount',
      header: t('column.amount'),
      align: 'end',
      render: (row) => formatCurrency(row.amount, locale, row.currency),
    },
    {
      key: 'issueDate',
      header: t('column.issueDate'),
      render: (row) =>
        row.issueDate ? formatDate(row.issueDate, locale) : tCommon('labels.unknown'),
    },
    {
      key: 'dueDate',
      header: t('column.dueDate'),
      sortable: true,
      render: (row) => (row.dueDate ? formatDate(row.dueDate, locale) : tCommon('labels.unknown')),
    },
    {
      key: 'status',
      header: t('column.status'),
      render: (row) => (
        <span className={styles.inline}>
          <StatusPill label={statusLabel(row.status)} status={row.status} />
          {row.eRechnung ? (
            <Badge tone="info" variant="outline">
              E-Rechnung
            </Badge>
          ) : null}
        </span>
      ),
    },
  ];

  return (
    <ListPage<InvoiceSummary>
      screenId="invoices"
      namespace="invoices"
      load={(params, signal) => api.invoices.list(params, signal)}
      columns={columns}
      sortableKeys={['number', 'dueDate']}
      filters={[
        {
          key: 'status',
          label: t('filter.status'),
          options: STATUS_VALUES.map((value) => ({ value, label: statusLabel(value) })),
        },
        {
          key: 'direction',
          label: t('filter.direction'),
          options: DIRECTION_VALUES.map((value) => ({ value, label: directionLabel(value) })),
        },
      ]}
      notice={<InlineAlert tone="info">{t('erechnung.notice')}</InlineAlert>}
      createTitle={t('action.new')}
      createFields={[
        { name: 'number', label: t('column.number'), required: true },
        { name: 'counterparty', label: t('column.counterparty'), required: true },
        { name: 'amount', label: t('column.amount'), type: 'number', required: true },
        { name: 'dueDate', label: t('column.dueDate'), type: 'date' },
        {
          name: 'direction',
          label: t('filter.direction'),
          type: 'select',
          options: DIRECTION_VALUES.map((value) => ({ value, label: directionLabel(value) })),
        },
      ]}
      onCreate={(values) =>
        api.invoices.create({
          number: values.number ?? '',
          counterparty: values.counterparty ?? '',
          amount: values.amount ?? '0',
          dueDate: values.dueDate,
          direction: (values.direction ?? 'incoming') as InvoiceSummary['direction'],
        })
      }
    />
  );
}
