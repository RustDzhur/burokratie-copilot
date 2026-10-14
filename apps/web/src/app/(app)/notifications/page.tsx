'use client';

import Link from 'next/link';
import { useState } from 'react';
import { ListPage } from '@/components/app/list-page';
import { Badge, StatusPill } from '@/components/ui';
import type { Column } from '@/components/ui';
import { Button } from '@/components/ui/button';
import { InlineAlert } from '@/components/ui/feedback';
import { Dialog } from '@/components/ui/overlay';
import { useToast } from '@/components/ui/toast';
import { formatDateTime } from '@/i18n/format';
import { useLocale, useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { useNotificationText } from '@/lib/format/notification-text';
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
  const textOf = useNotificationText();

  const [reloadToken, setReloadToken] = useState(0);
  const [preferencesOpen, setPreferencesOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [opened, setOpened] = useState<NotificationSummary | null>(null);

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

  // Opening a message is reading it: it is marked as read at once, so "unread" always means "not yet looked at".
  const open = (row: NotificationSummary): void => {
    setOpened(row);
    if (!row.read) {
      void api.notifications
        .markRead(row.id)
        .then(() => setReloadToken((value) => value + 1))
        .catch(() => undefined);
    }
  };

  const columns: readonly Column<NotificationSummary>[] = [
    {
      key: 'title',
      header: t('column.title'),
      render: (row) => (
        <button
          type="button"
          onClick={() => open(row)}
          style={{
            all: 'unset',
            cursor: 'pointer',
            textDecoration: 'underline',
            fontWeight: row.read ? 400 : 600,
          }}
        >
          {textOf(row).title}
        </button>
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
            <Button variant="secondary" iconLeft="gear" onClick={() => setPreferencesOpen(true)}>
              {t('action.settings')}
            </Button>
          </>
        }
        notice={
          <>
            <InlineAlert tone="info">{t('explain')}</InlineAlert>
            <InlineAlert tone="info">{t('privacy.notice')}</InlineAlert>
          </>
        }
        rowActions={(row) =>
          row.read ? null : (
            <Button
              variant="ghost"
              size="sm"
              disabled={busy}
              onClick={() => void run(() => api.notifications.markRead(row.id))}
            >
              {t('action.markRead')}
            </Button>
          )
        }
      />
      <Dialog
        open={opened !== null}
        onClose={() => setOpened(null)}
        title={opened ? textOf(opened).title : ''}
        description={
          opened
            ? `${t(`category.${opened.category}` as 'category.task')} · ${formatDateTime(opened.receivedAt, locale)}`
            : undefined
        }
      >
        {opened ? (
          <div style={{ display: 'grid', gap: '0.75rem' }}>
            <p style={{ margin: 0, whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>
              {textOf(opened).body ?? t('detail.noBody')}
            </p>
            <p style={{ margin: 0, fontSize: '0.875rem' }}>
              {t('detail.channels')}:{' '}
              {opened.channels.length === 0
                ? tCommon('labels.none')
                : opened.channels.map((channel) => channelLabel(channel)).join(', ')}
            </p>
            {opened.link ? (
              <p style={{ margin: 0 }}>
                <Link href={opened.link}>{t('detail.open')}</Link>
              </p>
            ) : null}
          </div>
        ) : null}
      </Dialog>
      <NotificationPreferencesDialog
        open={preferencesOpen}
        onClose={() => setPreferencesOpen(false)}
      />
    </div>
  );
}
