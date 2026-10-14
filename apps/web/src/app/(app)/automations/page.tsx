'use client';
import { ListPage } from '@/components/app/list-page';
import { StatusPill } from '@/components/ui';
import type { Column } from '@/components/ui';
import { formatDate } from '@/i18n/format';
import { useLocale, useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import type { AutomationSummary } from '@/lib/api/types';
import { useGroupedLabels, useStatusLabels } from '@/lib/format/status';
import styles from '@/styles/list-cells.module.css';

const STATUS_VALUES = ['active', 'paused', 'error', 'draft'] as const;
const TRIGGER_VALUES = [
  'documentReceived',
  'deadlineApproaching',
  'invoiceReceived',
  'mailReceived',
  'schedule',
] as const;

export default function AutomationsPage() {
  const t = useTranslations('automations');
  const tCommon = useTranslations('common');
  const locale = useLocale();
  const statusLabel = useStatusLabels('automations');
  const triggerLabel = useGroupedLabels('automations', 'trigger');

  const columns: readonly Column<AutomationSummary>[] = [
    {
      key: 'name',
      header: t('column.name'),
      sortable: true,
      link: (row) => `/automations/${row.id}`,
      render: (row) => row.name,
    },
    { key: 'trigger', header: t('column.trigger'), render: (row) => triggerLabel(row.trigger) },
    {
      key: 'lastRun',
      header: t('column.lastRun'),
      sortable: true,
      render: (row) =>
        row.lastRunAt ? formatDate(row.lastRunAt, locale) : tCommon('labels.unknown'),
    },
    {
      key: 'runs',
      header: t('column.runs'),
      align: 'end',
      render: (row) => row.runsLast30Days ?? 0,
    },
    {
      key: 'status',
      header: t('column.status'),
      render: (row) => (
        <span className={styles.inline}>
          <StatusPill label={statusLabel(row.status)} status={row.status} />
          {row.status === 'error' ? <span className={styles.note}>{t('errorHint')}</span> : null}
        </span>
      ),
    },
  ];

  return (
    <ListPage<AutomationSummary>
      screenId="automations"
      namespace="automations"
      deletable={{ kind: 'AUTOMATION', rowLabel: (row) => row.name }}
      load={(params, signal) => api.automations.list(params, signal)}
      columns={columns}
      sortableKeys={['name', 'lastRun']}
      filters={[
        {
          key: 'status',
          label: t('filter.status'),
          options: STATUS_VALUES.map((value) => ({ value, label: statusLabel(value) })),
        },
      ]}
      createTitle={t('action.new')}
      createFields={[
        { name: 'name', label: t('column.name'), required: true },
        {
          name: 'trigger',
          label: t('column.trigger'),
          type: 'select',
          options: TRIGGER_VALUES.map((value) => ({ value, label: triggerLabel(value) })),
        },
      ]}
      onCreate={(values) =>
        api.automations.create({
          name: values.name ?? '',
          trigger: values.trigger ?? 'documentReceived',
        })
      }
    />
  );
}
