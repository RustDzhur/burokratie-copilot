'use client';
import { ListPage } from '@/components/app/list-page';
import { Badge, StatusPill } from '@/components/ui';
import type { Column } from '@/components/ui';
import { formatDate } from '@/i18n/format';
import { useLocale, useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import type { TaskSummary } from '@/lib/api/types';
import { useGroupedLabels, useStatusLabels } from '@/lib/format/status';
import styles from '@/styles/list-cells.module.css';

const STATUS_VALUES = ['todo', 'doing', 'blocked', 'done'] as const;
const PRIORITY_VALUES = ['low', 'normal', 'high', 'urgent'] as const;

export default function TasksPage() {
  const t = useTranslations('tasks');
  const tCommon = useTranslations('common');
  const locale = useLocale();
  const statusLabel = useStatusLabels('tasks');
  const priorityLabel = useGroupedLabels('tasks', 'priority');

  const columns: readonly Column<TaskSummary>[] = [
    { key: 'title', header: t('column.title'), sortable: true, render: (row) => row.title },
    {
      key: 'dueDate',
      header: t('column.dueDate'),
      sortable: true,
      render: (row) =>
        row.dueDate ? (
          <span className={styles.inline}>
            {formatDate(row.dueDate, locale)}
            {row.overdue ? (
              <Badge tone="danger" variant="outline">
                {t('due.overdue')}
              </Badge>
            ) : null}
          </span>
        ) : (
          tCommon('labels.none')
        ),
    },
    {
      key: 'priority',
      header: t('column.priority'),
      render: (row) => (
        <Badge
          tone={
            row.priority === 'urgent' ? 'danger' : row.priority === 'high' ? 'warning' : 'neutral'
          }
        >
          {priorityLabel(row.priority)}
        </Badge>
      ),
    },
    {
      key: 'assignee',
      header: t('column.assignee'),
      render: (row) => row.assigneeName ?? tCommon('labels.none'),
    },
    {
      key: 'status',
      header: t('column.status'),
      render: (row) => <StatusPill label={statusLabel(row.status)} status={row.status} />,
    },
  ];

  return (
    <ListPage<TaskSummary>
      screenId="tasks"
      namespace="tasks"
      load={(params, signal) => api.tasks.list(params, signal)}
      columns={columns}
      sortableKeys={['title', 'dueDate']}
      filters={[
        {
          key: 'status',
          label: t('filter.status'),
          options: STATUS_VALUES.map((value) => ({ value, label: statusLabel(value) })),
        },
        {
          key: 'priority',
          label: t('filter.priority'),
          options: PRIORITY_VALUES.map((value) => ({ value, label: priorityLabel(value) })),
        },
      ]}
      createTitle={t('action.new')}
      createFields={[
        { name: 'title', label: t('column.title'), required: true },
        { name: 'dueDate', label: t('column.dueDate'), type: 'date' },
        {
          name: 'priority',
          label: t('column.priority'),
          type: 'select',
          options: PRIORITY_VALUES.map((value) => ({ value, label: priorityLabel(value) })),
        },
      ]}
      onCreate={(values) =>
        api.tasks.create({
          title: values.title ?? '',
          dueDate: values.dueDate,
          priority: (values.priority ?? 'normal') as TaskSummary['priority'],
        })
      }
    />
  );
}
