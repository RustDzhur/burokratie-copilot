'use client';
import { ListPage } from '@/components/app/list-page';
import { StatusPill } from '@/components/ui';
import type { Column } from '@/components/ui';
import { formatDate } from '@/i18n/format';
import { useLocale, useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import type { ContractSummary } from '@/lib/api/types';
import { useStatusLabels } from '@/lib/format/status';
import styles from '@/styles/list-cells.module.css';

const STATUS_VALUES = ['draft', 'active', 'expiring', 'ended'] as const;

export default function ContractsPage() {
  const t = useTranslations('contracts');
  const tCommon = useTranslations('common');
  const locale = useLocale();
  const statusLabel = useStatusLabels('contracts');

  const columns: readonly Column<ContractSummary>[] = [
    { key: 'partner', header: t('column.partner'), sortable: true, link: (row) => `/contracts/${row.id}`, render: (row) => row.partner },
    { key: 'type', header: t('column.type'), render: (row) => row.contractType },
    {
      key: 'startDate',
      header: t('column.startDate'),
      render: (row) =>
        row.startDate ? formatDate(row.startDate, locale) : tCommon('labels.unknown'),
    },
    {
      key: 'endDate',
      header: t('column.endDate'),
      sortable: true,
      render: (row) => (row.endDate ? formatDate(row.endDate, locale) : tCommon('labels.unknown')),
    },
    {
      key: 'noticePeriod',
      header: t('column.noticePeriod'),
      render: (row) => row.noticePeriod ?? tCommon('labels.unknown'),
    },
    {
      key: 'status',
      header: t('column.status'),
      render: (row) => (
        <span className={styles.inline}>
          <StatusPill label={statusLabel(row.status)} status={row.status} />
          {row.status === 'expiring' && row.noticeDeadline ? (
            <span className={styles.note}>
              {t('warning.expiringSoon', { date: formatDate(row.noticeDeadline, locale) })}
            </span>
          ) : null}
        </span>
      ),
    },
  ];

  return (
    <ListPage<ContractSummary>
      screenId="contracts"
      namespace="contracts"
      deletable={{ kind: 'CONTRACT', rowLabel: (row) => row.partner }}
      load={(params, signal) => api.contracts.list(params, signal)}
      columns={columns}
      sortableKeys={['partner', 'endDate']}
      filters={[
        {
          key: 'status',
          label: t('filter.status'),
          options: STATUS_VALUES.map((value) => ({ value, label: statusLabel(value) })),
        },
      ]}
      createTitle={t('action.new')}
      createFields={[
        { name: 'partner', label: t('column.partner'), required: true },
        { name: 'contractType', label: t('column.type'), required: true },
        { name: 'startDate', label: t('column.startDate'), type: 'date' },
        { name: 'endDate', label: t('column.endDate'), type: 'date' },
      ]}
      onCreate={(values) =>
        api.contracts.create({
          partner: values.partner ?? '',
          contractType: values.contractType ?? '',
          startDate: values.startDate,
          endDate: values.endDate,
        })
      }
    />
  );
}
