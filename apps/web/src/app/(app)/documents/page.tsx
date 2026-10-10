'use client';
import { ExportButton } from '@/components/app/export-button';
import { ListPage } from '@/components/app/list-page';
import { StatusPill } from '@/components/ui';
import type { Column } from '@/components/ui';
import { formatDate } from '@/i18n/format';
import { useLocale, useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import type { DocumentSummary } from '@/lib/api/types';
import { useStatusLabels } from '@/lib/format/status';

const STATUS_VALUES = [
  'received',
  'processing',
  'needs_review',
  'processed',
  'failed',
  'archived',
] as const;

export default function DocumentsPage() {
  const t = useTranslations('documents');
  const tCommon = useTranslations('common');
  const locale = useLocale();
  const statusLabel = useStatusLabels('documents');

  const columns: readonly Column<DocumentSummary>[] = [
    {
      key: 'title',
      header: t('column.title'),
      sortable: true,
      link: (row) => `/documents/${row.id}`,
      render: (row) => row.title,
    },
    { key: 'type', header: t('column.type'), render: (row) => row.documentType },
    {
      key: 'status',
      header: t('column.status'),
      render: (row) => <StatusPill label={statusLabel(row.status)} status={row.status} />,
    },
    {
      key: 'receivedAt',
      header: t('column.createdAt'),
      sortable: true,
      render: (row) => formatDate(row.receivedAt, locale),
    },
    {
      key: 'retentionUntil',
      header: t('column.retentionUntil'),
      render: (row) =>
        row.retentionUntil ? formatDate(row.retentionUntil, locale) : tCommon('labels.unknown'),
    },
  ];

  return (
    <ListPage<DocumentSummary>
      screenId="documents"
      namespace="documents"
      load={(params, signal) => api.documents.list(params, signal)}
      columns={columns}
      sortableKeys={['title', 'receivedAt']}
      filters={[
        {
          key: 'status',
          label: t('filter.status'),
          options: STATUS_VALUES.map((value) => ({ value, label: statusLabel(value) })),
        },
      ]}
      headerActions={
        <ExportButton
          label={t('action.export')}
          filename="documents.csv"
          onExport={() => api.documents.export()}
        />
      }
    />
  );
}
