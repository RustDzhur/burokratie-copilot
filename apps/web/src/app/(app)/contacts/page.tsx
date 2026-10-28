'use client';
import { ListPage } from '@/components/app/list-page';
import type { Column } from '@/components/ui';
import { formatDate } from '@/i18n/format';
import { useLocale, useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import type { ContactSummary } from '@/lib/api/types';
import { useGroupedLabels } from '@/lib/format/status';

const TYPE_VALUES = ['person', 'organisation', 'authority'] as const;

export default function ContactsPage() {
  const t = useTranslations('contacts');
  const tCommon = useTranslations('common');
  const locale = useLocale();
  const typeLabel = useGroupedLabels('contacts', 'type');

  const columns: readonly Column<ContactSummary>[] = [
    { key: 'name', header: t('column.name'), sortable: true, link: (row) => `/contacts/${row.id}`, render: (row) => row.name },
    { key: 'type', header: t('column.type'), render: (row) => typeLabel(row.contactType) },
    {
      key: 'email',
      header: t('column.email'),
      render: (row) => row.email ?? tCommon('labels.none'),
    },
    {
      key: 'phone',
      header: t('column.phone'),
      render: (row) => row.phone ?? tCommon('labels.none'),
    },
    { key: 'city', header: t('column.city'), render: (row) => row.city ?? tCommon('labels.none') },
    {
      key: 'updatedAt',
      header: t('column.updatedAt'),
      sortable: true,
      render: (row) => formatDate(row.updatedAt, locale),
    },
  ];

  return (
    <ListPage<ContactSummary>
      screenId="contacts"
      namespace="contacts"
      deletable={{ kind: 'CONTACT', rowLabel: (row) => row.name }}
      load={(params, signal) => api.contacts.list(params, signal)}
      columns={columns}
      sortableKeys={['name', 'updatedAt']}
      filters={[
        {
          key: 'contactType',
          label: t('filter.type'),
          options: TYPE_VALUES.map((value) => ({ value, label: typeLabel(value) })),
        },
      ]}
      createTitle={t('action.new')}
      createFields={[
        { name: 'name', label: t('column.name'), required: true },
        {
          name: 'contactType',
          label: t('filter.type'),
          type: 'select',
          options: TYPE_VALUES.map((value) => ({ value, label: typeLabel(value) })),
        },
        { name: 'email', label: t('column.email'), type: 'email' },
      ]}
      onCreate={(values) =>
        api.contacts.create({
          name: values.name ?? '',
          contactType: (values.contactType ?? 'person') as ContactSummary['contactType'],
          email: values.email,
        })
      }
    />
  );
}
