'use client';
import { ListPage } from '@/components/app/list-page';
import { StatusPill } from '@/components/ui';
import type { Column } from '@/components/ui';
import { formatDate } from '@/i18n/format';
import { useLocale, useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import type { CaseSummary } from '@/lib/api/types';
import { useStatusLabels } from '@/lib/format/status';

const STATUS_VALUES = ['open', 'in_progress', 'waiting', 'closed'] as const;

export default function CasesPage() {
  const t = useTranslations('cases');
  const tCommon = useTranslations('common');
  const locale = useLocale();
  const statusLabel = useStatusLabels('cases');

  const columns: readonly Column<CaseSummary>[] = [
    { key: 'reference', header: t('column.reference'), render: (row) => row.reference },
    { key: 'title', header: t('column.title'), sortable: true, render: (row) => row.title },
    {
      key: 'status',
      header: t('column.status'),
      render: (row) => <StatusPill label={statusLabel(row.status)} status={row.status} />,
    },
    {
      key: 'documents',
      header: t('column.documents'),
      align: 'end',
      render: (row) => row.documentCount,
    },
    {
      key: 'dueDate',
      header: t('column.dueDate'),
      render: (row) =>
        row.nextDeadline ? formatDate(row.nextDeadline, locale) : tCommon('labels.none'),
    },
    {
      key: 'updatedAt',
      header: t('column.updatedAt'),
      sortable: true,
      render: (row) => formatDate(row.updatedAt, locale),
    },
  ];

  return (
    <ListPage<CaseSummary>
      screenId="cases"
      namespace="cases"
      deletable={{ kind: 'CASE', rowLabel: (row) => row.title }}
      load={(params, signal) => api.cases.list(params, signal)}
      columns={columns}
      sortableKeys={['title', 'updatedAt']}
      filters={[
        {
          key: 'status',
          label: t('filter.status'),
          options: STATUS_VALUES.map((value) => ({ value, label: statusLabel(value) })),
        },
      ]}
      createTitle={t('action.new')}
      createFields={[
        { name: 'title', label: t('column.title'), required: true },
        { name: 'caseType', label: t('filter.type') },
      ]}
      onCreate={(values) =>
        api.cases.create({ title: values.title ?? '', caseType: values.caseType })
      }
    />
  );
}
