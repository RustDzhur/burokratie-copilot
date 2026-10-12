'use client';

import { useState } from 'react';
import { ListPage } from '@/components/app/list-page';
import { Badge, StatusPill } from '@/components/ui';
import type { Column } from '@/components/ui';
import { Button } from '@/components/ui/button';
import { InlineAlert } from '@/components/ui/feedback';
import { useToast } from '@/components/ui/toast';
import { formatDateTime } from '@/i18n/format';
import { useLocale, useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { toApiError } from '@/lib/api/errors';
import type { NotificationSummary } from '@/lib/api/types';
import { NotificationPreferencesDialog } from './notification-preferences-dialog';

const CATEGORIES = [
  'task',
  'deadline',
  'mail',
  'review',
  'approval',
  'integration',
  'automation',
  'archive',
] as const;

export default function NotificationsPage() {
  const t = useTranslations('notifications');
  const tCommon = useTranslations('common');
  const tErrors = useTranslations('errors');
  const locale = useLocale();
  const toast = useToast();

  const [reloadToken, setReloadToken] = useState(0);
  const [preferencesOpen, setPreferencesOpen] = useState(false);
  const [busy, setBusy] = useState(false);

  const channelLabel = (channel: 'in_app' | 'email' | 'push'): string =>
    t(`channel.${channel === 'in_app' ? 'inApp' : channel}` as 'channel.inApp');

  const run = async (action: () => Promise<void>): Promise<void> => {
    setBusy(true);
    try {
      await action();
      setReloadToken((value) => value + 1);
    } catch (error) {
      toast.error(tErrors(toApiError(error).kind));
    } finally {
      setBusy(false);
    }
  };

  const columns: readonly Column<NotificationSummary>[] = [
    {
      key: 'title',
      header: t('column.title'),
      render: (row) => (
        <span style={{ fontWeight: row.read ? 400 : 600 }}>{row.title}</span>
      ),
    },
    {
      key: 'category',
      header: t('column.category'),
      render: (row) => t(`category.${row.category}` as 'category.task'),
    },
    {
      key: 'channels',
      header: t('column.channel'),
      render: (row) =>
        row.channels.length === 0 ? (
          tCommon('labels.none')
        ) : (
          <>
            {row.channels.map((channel) => (
              <Badge key={channel}>{channelLabel(channel)}</Badge>
            ))}
          </>
        ),
    },
    {
      key: 'receivedAt',
      header: t('column.receivedAt'),
      render: (row) => formatDateTime(row.receivedAt, locale),
    },
    {
      key: 'status',
      header: t('filter.status'),
      render: (row) => (
        <StatusPill
          label={row.read ? t('status.read') : t('status.unread')}
          tone={row.read ? 'neutral' : 'info'}
        />
      ),
    },
  ];

  return (
    <div key={reloadToken}>
      <ListPage<NotificationSummary>
        screenId="notifications"
        namespace="notifications"
        load={(params, signal) => api.notifications.list(params, signal)}
        columns={columns}
        searchable={false}
        pageSize={50}
        emptyIcon="bell"
        filters={[
          {
            key: 'category',
            label: t('filter.category'),
            options: CATEGORIES.map((value) => ({
              value,
              label: t(`category.${value}` as 'category.task'),
            })),
          },
        ]}
        headerActions={
          <>
            <Button
              variant="secondary"
              loading={busy}
              onClick={() => void run(() => api.notifications.markAllRead())}
            >
              {t('action.markAllRead')}
            </Button>
            <Button variant="secondary" iconLeft="settings" onClick={() => setPreferencesOpen(true)}>
              {t('action.settings')}
            </Button>
          </>
        }
        notice={<InlineAlert tone="info">{t('privacy.notice')}</InlineAlert>}
        rowActions={(row) =>
          row.read ? null : (
            <Button
              variant="ghost"
              size="sm"
              disabled={busy}
              onClick={() => void run(() => api.notifications.markRead(row.id))}
            >
              {t('status.read')}
            </Button>
          )
        }
      />
      <NotificationPreferencesDialog
        open={preferencesOpen}
        onClose={() => setPreferencesOpen(false)}
      />
    </div>
  );
}
