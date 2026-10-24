'use client';

import { useState } from 'react';
import { CalendarConnectionDialog } from '@/components/app/calendar-connection-dialog';
import { CalendarFeedDialog } from '@/components/app/calendar-feed-dialog';
import { Card, CardBody, CardHeader, StatusPill } from '@/components/ui';
import { Button } from '@/components/ui/button';
import { useToast } from '@/components/ui/toast';
import { formatDate } from '@/i18n/format';
import { useLocale, useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { useApiResource } from '@/lib/api/hooks';
import { toApiError } from '@/lib/api/errors';
import styles from './integrations.module.css';

/**
 * The calendar integrations: the private feed a phone subscribes to (unchanged), and the CalDAV
 * connection into an external calendar (iCloud with an app-specific password, or a generic CalDAV
 * server) that the platform writes its events into.
 */
export function CalendarCard() {
  const t = useTranslations('integrations');
  const tErrors = useTranslations('errors');
  const locale = useLocale();
  const toast = useToast();
  const [feedOpen, setFeedOpen] = useState(false);
  const [connectOpen, setConnectOpen] = useState(false);
  const [reloadKey, setReloadKey] = useState(0);
  const [syncing, setSyncing] = useState(false);

  const { state } = useApiResource((signal) => api.calendar.connection.status(signal), [
    reloadKey,
  ]);

  const sync = async (): Promise<void> => {
    setSyncing(true);
    try {
      const result = await api.calendar.connection.sync();
      if (result.status === 'SUCCESS') {
        toast.success(
          t('calendarExternal.syncDone', {
            pushed: result.pushed,
            deletedRemote: result.deletedRemote,
          }),
        );
      } else if (result.status === 'PARTIAL') {
        toast.warning(t('calendarExternal.syncPartial', { failed: result.failed }));
      } else {
        toast.error(t('calendarExternal.syncFailed'));
      }
      setReloadKey((value) => value + 1);
    } catch (caught) {
      toast.error(t('calendarExternal.syncFailed'), tErrors(toApiError(caught).kind));
    } finally {
      setSyncing(false);
    }
  };

  const disconnect = async (): Promise<void> => {
    if (!window.confirm(t('calendarExternal.disconnectConfirm'))) return;
    try {
      await api.calendar.connection.disconnect();
      toast.success(t('calendarExternal.disconnected'));
      setReloadKey((value) => value + 1);
    } catch (caught) {
      toast.error(t('calendarExternal.disconnectFailed'), tErrors(toApiError(caught).kind));
    }
  };

  const connected = state.status === 'ready' && state.data ? state.data : null;

  return (
    <>
      <section className={styles.section} aria-labelledby="calendar-heading">
        <Card>
          <CardHeader
            title={t('calendar.title')}
            description={t('calendar.description')}
            titleId="calendar-heading"
            headingLevel={2}
          />
          <CardBody>
            <ul className={styles.meta}>
              <li>{t('calendar.iphone')}</li>
              <li>{t('calendar.google')}</li>
              <li>{t('calendar.android')}</li>
            </ul>
            <p className={styles.meta}>{t('calendar.note')}</p>
            <Button variant="primary" onClick={() => setFeedOpen(true)}>
              {t('calendar.connect')}
            </Button>
          </CardBody>
        </Card>
      </section>

      <section className={styles.section} aria-labelledby="calendar-external-heading">
        <Card>
          <CardHeader
            title={t('calendarExternal.title')}
            description={t('calendarExternal.description')}
            titleId="calendar-external-heading"
            headingLevel={2}
          />
          <CardBody>
            {connected === null && state.status === 'loading' ? (
              <p className={styles.meta}>{t('calendarExternal.loading')}</p>
            ) : connected === null ? (
              <>
                <p className={styles.meta}>{t('calendarExternal.notConnected')}</p>
                <Button variant="primary" onClick={() => setConnectOpen(true)}>
                  {t('calendarExternal.connect')}
                </Button>
              </>
            ) : (
              <>
                <ul className={styles.list}>
                  <li className={styles.row}>
                    <div>
                      <strong>{connected.account}</strong>
                      <p className={styles.meta}>
                        {connected.calendarName
                          ? t('calendarExternal.calendar', { name: connected.calendarName })
                          : connected.calendarPath}
                      </p>
                      <p className={styles.meta}>
                        {connected.lastSyncAt
                          ? t('calendarExternal.lastSync', {
                              date: formatDate(connected.lastSyncAt, locale),
                            })
                          : t('calendarExternal.neverSynced')}
                      </p>
                      {connected.error ? (
                        <p className={`${styles.meta} ${styles.error}`}>{connected.error}</p>
                      ) : null}
                    </div>
                    <StatusPill
                      label={t(
                        `calendarExternal.status.${connected.status}` as 'calendarExternal.status.CONNECTED',
                      )}
                      status={connected.status === 'CONNECTED' ? 'connected' : 'error'}
                    />
                    <div style={{ display: 'flex', gap: '0.5rem', flexWrap: 'wrap' }}>
                      <Button
                        variant="secondary"
                        size="sm"
                        loading={syncing}
                        onClick={() => void sync()}
                      >
                        {t('calendarExternal.sync')}
                      </Button>
                      <Button variant="ghost" size="sm" onClick={() => void disconnect()}>
                        {t('calendarExternal.disconnect')}
                      </Button>
                    </div>
                  </li>
                </ul>
              </>
            )}
          </CardBody>
        </Card>
      </section>

      <CalendarFeedDialog open={feedOpen} onClose={() => setFeedOpen(false)} />
      <CalendarConnectionDialog
        open={connectOpen}
        onClose={() => setConnectOpen(false)}
        onConnected={() => setReloadKey((value) => value + 1)}
      />
    </>
  );
}
