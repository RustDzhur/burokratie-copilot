'use client';
import { useState } from 'react';
import { ListPage } from '@/components/app/list-page';
import type { FormFieldDefinition } from '@/components/app/create-record-dialog';
import { StatusPill } from '@/components/ui';
import type { Column } from '@/components/ui';
import { InlineAlert } from '@/components/ui/feedback';
import { useToast } from '@/components/ui/toast';
import { formatDate } from '@/i18n/format';
import { useLocale, useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { toApiError } from '@/lib/api/errors';
import type { IntegrationSummary } from '@/lib/api/types';
import { useGroupedLabels, useStatusLabels } from '@/lib/format/status';
import { CalendarCard } from './calendar-card';
import { IntegrationsOverview } from './integrations-overview';
import { TelegramCard } from './telegram-card';

const STATUS_VALUES = ['connected', 'disconnected', 'error', 'pending'] as const;
const CATEGORY_VALUES = ['bank', 'mail', 'storage', 'calendar', 'device', 'identity'] as const;

/** IMAP servers of the usual providers, so a person only has to pick the provider and type the password. */
const IMAP_HOSTS: Readonly<Record<string, string>> = {
  gmail: 'imap.gmail.com',
  outlook: 'outlook.office365.com',
  gmx: 'imap.gmx.net',
  webde: 'imap.web.de',
  ionos: 'imap.ionos.de',
  tonline: 'secureimap.t-online.de',
};
const PROVIDERS = ['gmail', 'outlook', 'gmx', 'webde', 'ionos', 'tonline', 'other'] as const;

export default function IntegrationsPage() {
  const t = useTranslations('integrations');
  const tCommon = useTranslations('common');
  const tErrors = useTranslations('errors');
  const toast = useToast();
  const locale = useLocale();
  const statusLabel = useStatusLabels('integrations');
  const categoryLabel = useGroupedLabels('integrations', 'category');
  const [reloadKey, setReloadKey] = useState(0);

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

  const createFields: readonly FormFieldDefinition[] = [
    {
      name: 'provider',
      label: t('create.provider'),
      type: 'select',
      required: true,
      options: PROVIDERS.map((value) => ({
        value,
        label: t(`create.provider.${value}` as 'create.provider.gmail'),
      })),
    },
    { name: 'email', label: t('create.email'), type: 'email', required: true },
    {
      name: 'password',
      label: t('create.password'),
      type: 'password',
      required: true,
      hint: t('create.passwordHint'),
    },
    { name: 'host', label: t('create.host'), type: 'text' },
  ];

  const connect = async (values: Record<string, string>): Promise<void> => {
    const provider = values.provider ?? 'other';
    const host = (provider === 'other' ? values.host : IMAP_HOSTS[provider])?.trim();
    if (!host) throw new Error(t('create.hostMissing'));
    const email = (values.email ?? '').trim();
    const created = await api.mailAccounts.connect({
      displayName: email,
      emailAddress: email,
      password: values.password ?? '',
      imapHost: host,
      imapPort: 993,
    });
    setReloadKey((current) => current + 1);
    try {
      const result = await api.mailAccounts.collect(created.accountId);
      toast.success(email, t('create.collected', { stored: result.stored }));
    } catch (caught) {
      toast.warning(email, t('create.collectFailed', { reason: tErrors(toApiError(caught).kind) }));
    }
    setReloadKey((current) => current + 1);
  };

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
      createFields={createFields}
      onCreate={connect}
      createTitle={t('create.title')}
      notice={
        <>
          <CalendarCard />
          <TelegramCard />
          <InlineAlert tone="info">{t('credentials.notice')}</InlineAlert>
          <IntegrationsOverview reloadKey={reloadKey} />
        </>
      }
    />
  );
}
