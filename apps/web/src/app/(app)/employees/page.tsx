'use client';
import { ListPage } from '@/components/app/list-page';
import { StatusPill } from '@/components/ui';
import type { Column } from '@/components/ui';
import { formatDate } from '@/i18n/format';
import { useLocale, useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import type { MemberSummary } from '@/lib/api/types';
import { useGroupedLabels, useStatusLabels } from '@/lib/format/status';

const STATUS_VALUES = ['active', 'invited', 'disabled'] as const;
const ROLE_VALUES = ['owner', 'admin', 'member', 'accountant', 'auditor'] as const;

export default function EmployeesPage() {
  const t = useTranslations('employees');
  const tCommon = useTranslations('common');
  const locale = useLocale();
  const statusLabel = useStatusLabels('employees');
  const roleLabel = useGroupedLabels('employees', 'role');

  const columns: readonly Column<MemberSummary>[] = [
    { key: 'name', header: t('column.name'), sortable: true, render: (row) => row.name },
    { key: 'email', header: t('column.email'), render: (row) => row.email },
    { key: 'role', header: t('column.role'), render: (row) => roleLabel(row.role) },
    {
      key: 'status',
      header: t('column.status'),
      render: (row) => <StatusPill label={statusLabel(row.status)} status={row.status} />,
    },
    {
      key: 'lastActive',
      header: t('column.lastActive'),
      sortable: true,
      render: (row) =>
        row.lastActiveAt ? formatDate(row.lastActiveAt, locale) : tCommon('labels.unknown'),
    },
  ];

  return (
    <ListPage<MemberSummary>
      screenId="employees"
      namespace="employees"
      load={(params, signal) => api.members.list(params, signal)}
      columns={columns}
      sortableKeys={['name', 'lastActive']}
      filters={[
        {
          key: 'role',
          label: t('filter.role'),
          options: ROLE_VALUES.map((value) => ({ value, label: roleLabel(value) })),
        },
        {
          key: 'status',
          label: t('filter.status'),
          options: STATUS_VALUES.map((value) => ({ value, label: statusLabel(value) })),
        },
      ]}
      createTitle={t('action.invite')}
      createFields={[
        { name: 'email', label: t('column.email'), type: 'email', required: true },
        {
          name: 'role',
          label: t('column.role'),
          type: 'select',
          options: ROLE_VALUES.map((value) => ({ value, label: roleLabel(value) })),
        },
      ]}
      onCreate={(values) =>
        api.members.invite({
          email: values.email ?? '',
          role: (values.role ?? 'member') as MemberSummary['role'],
        })
      }
    />
  );
}
