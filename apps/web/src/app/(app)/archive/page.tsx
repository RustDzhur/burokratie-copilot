'use client';
import { ExportButton } from '@/components/app/export-button';
import { ListPage } from '@/components/app/list-page';
import { Badge, StatusPill } from '@/components/ui';
import type { Column } from '@/components/ui';
import { InlineAlert } from '@/components/ui/feedback';
import { formatDate } from '@/i18n/format';
import { useLocale, useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import type { ArchiveEntry } from '@/lib/api/types';
import { useStatusLabels } from '@/lib/format/status';

const STATUS_VALUES = ['retention_active', 'retention_expired', 'unknown'] as const;

export default function ArchivePage() {
  const t = useTranslations('archive');
  const tCommon = useTranslations('common');
  const locale = useLocale();
  const statusLabel = useStatusLabels('archive');

  const columns: readonly Column<ArchiveEntry>[] = [
    { key: 'title', header: t('column.title'), sortable: true, render: (row) => row.title },
    {
      key: 'archivedAt',
      header: t('column.archivedAt'),
      render: (row) =>
        row.archivedAt ? formatDate(row.archivedAt, locale) : tCommon('labels.unknown'),
    },
    {
      key: 'retentionUntil',
      header: t('column.retentionUntil'),
      sortable: true,
      render: (row) =>
        row.retentionUntil ? formatDate(row.retentionUntil, locale) : t('retention.unknown'),
    },
    {
      key: 'legalHold',
      header: t('column.legalHold'),
      render: (row) => (
        <Badge tone={row.legalHold ? 'danger' : 'success'} variant="outline">
          {row.legalHold ? t('legalHold.yes') : t('legalHold.no')}
        </Badge>
      ),
    },
    {
      key: 'status',
      header: t('column.status'),
      render: (row) => <StatusPill label={statusLabel(row.status)} status={row.status} />,
    },
  ];

  return (
    <ListPage<ArchiveEntry>
      screenId="archive"
      namespace="archive"
      deletable={{ kind: 'DOCUMENT', rowLabel: (row) => row.title }}
      load={(params, signal) => api.archive.list(params, signal)}
      columns={columns}
      sortableKeys={['title', 'retentionUntil']}
      filters={[
        {
          key: 'status',
          label: t('filter.retention'),
          options: STATUS_VALUES.map((value) => ({ value, label: statusLabel(value) })),
        },
      ]}
      headerActions={
        <ExportButton
          label={t('action.export')}
          filename="archiv.csv"
          onExport={() => api.archive.export()}
        />
      }
      notice={<InlineAlert tone="info">{t('notice')}</InlineAlert>}
    />
  );
}
