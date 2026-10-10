'use client';
import { ListPage } from '@/components/app/list-page';
import { StatusPill } from '@/components/ui';
import type { Column } from '@/components/ui';
import { InlineAlert } from '@/components/ui/feedback';
import { formatDate } from '@/i18n/format';
import { useLocale, useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import type { IntegrationSummary } from '@/lib/api/types';
import { useGroupedLabels, useStatusLabels } from '@/lib/format/status';

const STATUS_VALUES = ['connected', 'disconnected', 'error', 'pending'] as const;
const CATEGORY_VALUES = ['bank', 'mail', 'storage', 'calendar', 'device', 'identity'] as const;

export default function IntegrationsPage() {
  const t = useTranslations('integrations');
  const tCommon = useTranslations('common');
  const locale = useLocale();
  const statusLabel = useStatusLabels('integrations');
  const categoryLabel = useGroupedLabels('integrations', 'category');

  const columns: readonly Column<IntegrationSummary>[] = [
    { key: 'name', header: t('column.name'), render: (row) => row.name },
    {
      key: 'category',
      header: t('column.category'),
      sortable: true,
      render: (row) => categoryLabel(row.category),
    },
    {
      key: 'status',
      header: t('column.status'),
      render: (row) => <StatusPill label={statusLabel(row.status)} status={row.status} />,
    },
    {
      key: 'lastSync',
      header: t('column.lastSync'),
      render: (row) =>
        row.lastSyncAt ? formatDate(row.lastSyncAt, locale) : tCommon('labels.unknown'),
    },
  ];

  return (
    <ListPage<IntegrationSummary>
      screenId="integrations"
      namespace="integrations"
      load={(params, signal) => api.integrations.list(params, signal)}
      columns={columns}
      sortableKeys={['name']}
      filters={[
        {
          key: 'status',
          label: t('filter.status'),
          options: STATUS_VALUES.map((value) => ({ value, label: statusLabel(value) })),
        },
        {
          key: 'category',
          label: t('filter.category'),
          options: CATEGORY_VALUES.map((value) => ({ value, label: categoryLabel(value) })),
        },
      ]}
      notice={<InlineAlert tone="info">{t('credentials.notice')}</InlineAlert>}
    />
  );
}
