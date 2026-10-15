'use client';
import { useState } from 'react';
import { ListPage } from '@/components/app/list-page';
import { Badge, StatusPill } from '@/components/ui';
import { Button } from '@/components/ui/button';
import { useToast } from '@/components/ui/toast';
import { toApiError } from '@/lib/api/errors';
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
  const tErrors = useTranslations('errors');
  const toast = useToast();
  const [reloadToken, setReloadToken] = useState(0);
  const [busyId, setBusyId] = useState<string | null>(null);

  const move = async (task: TaskSummary, status: TaskSummary['status']): Promise<void> => {
    setBusyId(task.id);
    try {
      await api.tasks.setStatus(task.id, status);
      setReloadToken((value) => value + 1);
    } catch (error) {
      toast.error(tErrors(toApiError(error).kind));
    } finally {
      setBusyId(null);
    }
  };

  const columns: readonly Column<TaskSummary>[] = [
    { key: 'title', header: t('column.title'), sortable: true, link: (row) => `/tasks/${row.id}`, render: (row) => row.title },
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
    <div key={reloadToken}>
      <ListPage<TaskSummary>
        screenId="tasks"
        namespace="tasks"
        deletable={{ kind: 'TASK', rowLabel: (row) => row.title }}
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
            name: 'completionCriteria',
            label: t('column.completionCriteria'),
            type: 'textarea',
            required: true,
            hint: t('hint.completionCriteria'),
          },
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
            completionCriteria: values.completionCriteria,
          })
        }
        rowActions={(row) =>
          row.status === 'done' ? null : (
            <>
              {row.status === 'todo' || row.status === 'blocked' ? (
                <Button
                  variant="ghost"
                  size="sm"
                  disabled={busyId === row.id}
                  onClick={() => void move(row, 'doing')}
                >
                  {t('action.start')}
                </Button>
              ) : null}
              {row.status !== 'blocked' ? (
                <Button
                  variant="ghost"
                  size="sm"
                  disabled={busyId === row.id}
                  onClick={() => void move(row, 'blocked')}
                >
                  {t('action.block')}
                </Button>
              ) : null}
              <Button
                variant="ghost"
                size="sm"
                disabled={busyId === row.id}
                onClick={() => void move(row, 'done')}
              >
                {t('action.complete')}
              </Button>
            </>
          )
        }
      />
    </div>
  );
}
