'use client';
import { ExportButton } from '@/components/app/export-button';
import { ListPage } from '@/components/app/list-page';
import { StatusPill } from '@/components/ui';
import type { Column } from '@/components/ui';
import { InlineAlert } from '@/components/ui/feedback';
import { formatDate } from '@/i18n/format';
import { useLocale, useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import type { AuditEntry } from '@/lib/api/types';
import { useGroupedLabels } from '@/lib/format/status';

const RESULT_VALUES = ['success', 'failure', 'denied'] as const;

export default function AuditPage() {
  const t = useTranslations('audit');
  const tCommon = useTranslations('common');
  const locale = useLocale();
  const resultLabel = useGroupedLabels('audit', 'result');

  const columns: readonly Column<AuditEntry>[] = [
    {
      key: 'timestamp',
      header: t('column.timestamp'),
      sortable: true,
      render: (row) => formatDate(row.timestamp, locale, 'medium'),
    },
    { key: 'actor', header: t('column.actor'), render: (row) => row.actor },
    { key: 'action', header: t('column.action'), render: (row) => row.action },
    {
      key: 'object',
      header: t('column.object'),
      render: (row) => `${row.objectType}: ${row.objectLabel}`,
    },
    {
      key: 'result',
      header: t('column.result'),
      render: (row) => <StatusPill label={resultLabel(row.result)} status={row.result} />,
    },
    {
      key: 'correlationId',
      header: t('column.correlationId'),
      render: (row) => row.correlationId ?? tCommon('labels.none'),
    },
  ];

  return (
    <ListPage<AuditEntry>
      screenId="audit"
      namespace="audit"
      load={(params, signal) => api.audit.list(params, signal)}
      columns={columns}
      sortableKeys={['timestamp']}
      filters={[
        {
          key: 'result',
          label: t('filter.result'),
          options: RESULT_VALUES.map((value) => ({ value, label: resultLabel(value) })),
        },
      ]}
      headerActions={
        <ExportButton
          label={t('action.export')}
          filename="audit.csv"
          onExport={() => api.audit.export()}
        />
      }
      notice={<InlineAlert tone="info">{t('immutable.notice')}</InlineAlert>}
    />
  );
}
