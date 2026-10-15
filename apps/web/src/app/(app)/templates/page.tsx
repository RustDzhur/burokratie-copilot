'use client';
import Link from 'next/link';
import { ListPage } from '@/components/app/list-page';
import { StatusPill } from '@/components/ui';
import type { Column } from '@/components/ui';
import { formatDate } from '@/i18n/format';
import { useLocale, useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import type { TemplateSummary } from '@/lib/api/types';
import { useStatusLabels } from '@/lib/format/status';

const STATUS_VALUES = ['draft', 'published'] as const;

export default function TemplatesPage() {
  const t = useTranslations('templates');
  const locale = useLocale();
  const statusLabel = useStatusLabels('templates');

  const columns: readonly Column<TemplateSummary>[] = [
    { key: 'name', header: t('column.name'), sortable: true, render: (row) => row.name },
    { key: 'type', header: t('column.type'), render: (row) => row.templateType },
    {
      key: 'updatedAt',
      header: t('column.updatedAt'),
      sortable: true,
      render: (row) => formatDate(row.updatedAt, locale),
    },
    {
      key: 'status',
      header: t('column.status'),
      render: (row) => <StatusPill label={statusLabel(row.status)} status={row.status} />,
    },
  ];

  return (
    <>
      <p>
        <Link href="/templates/new">{t('visual.open')}</Link>
      </p>
      <ListPage<TemplateSummary>
      screenId="templates"
      namespace="templates"
      deletable={{ kind: 'TEMPLATE', rowLabel: (row) => row.name }}
      load={(params, signal) => api.templates.list(params, signal)}
      columns={columns}
      sortableKeys={['name', 'updatedAt']}
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
        { name: 'templateType', label: t('column.type'), required: true },
        { name: 'body', label: t('editor.title'), type: 'textarea' },
      ]}
      onCreate={(values) =>
        api.templates.create({
          name: values.name ?? '',
          templateType: values.templateType ?? '',
          body: values.body,
        })
      }
    />
    </>
  );
}
