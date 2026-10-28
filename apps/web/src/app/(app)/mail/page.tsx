'use client';
import { ListPage } from '@/components/app/list-page';
import { StatusPill } from '@/components/ui';
import type { Column } from '@/components/ui';
import { formatDate } from '@/i18n/format';
import { useLocale, useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import type { MailMessageSummary } from '@/lib/api/types';
import { useGroupedLabels, useStatusLabels } from '@/lib/format/status';
import { MailOAuthConnect } from './mail-oauth-connect';

const STATUS_VALUES = ['new', 'processed', 'needs_reply'] as const;
const FOLDER_VALUES = ['inbox', 'sent', 'drafts', 'archive'] as const;

export default function MailPage() {
  const t = useTranslations('mail');
  const locale = useLocale();
  const statusLabel = useStatusLabels('mail');
  const folderLabel = useGroupedLabels('mail', 'folder');

  const columns: readonly Column<MailMessageSummary>[] = [
    {
      key: 'subject',
      header: t('column.subject'),
      sortable: true,
      link: (row) => `/mail/${row.id}`,
      render: (row) => row.subject || '—',
    },
    { key: 'from', header: t('column.from'), render: (row) => row.from },
    {
      key: 'receivedAt',
      header: t('column.receivedAt'),
      sortable: true,
      render: (row) => formatDate(row.receivedAt, locale),
    },
    {
      key: 'attachments',
      header: t('column.attachments'),
      align: 'end',
      render: (row) => row.attachmentCount,
    },
    {
      key: 'status',
      header: t('column.status'),
      render: (row) => <StatusPill label={statusLabel(row.status)} status={row.status} />,
    },
  ];

  return (
    <ListPage<MailMessageSummary>
      screenId="mail"
      namespace="mail"
      deletable={{ kind: 'MAIL', rowLabel: (row) => row.subject }}
      load={(params, signal) => api.mail.list(params, signal)}
      columns={columns}
      sortableKeys={['subject', 'receivedAt']}
      filters={[
        {
          key: 'folder',
          label: t('filter.folder'),
          options: FOLDER_VALUES.map((value) => ({ value, label: folderLabel(value) })),
        },
        {
          key: 'status',
          label: t('filter.status'),
          options: STATUS_VALUES.map((value) => ({ value, label: statusLabel(value) })),
        },
      ]}
      notice={<MailOAuthConnect />}
    />
  );
}
