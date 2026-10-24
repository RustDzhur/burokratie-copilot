'use client';

import { useState } from 'react';
import { Button } from '@/components/ui/button';
import { InlineAlert } from '@/components/ui/feedback';
import { Dialog } from '@/components/ui/overlay';
import { Field, Input, Select } from '@/components/ui/form';
import { useToast } from '@/components/ui/toast';
import { useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { toApiError } from '@/lib/api/errors';
import type { CalDavCalendarInfo } from '@/lib/api/types';

interface CalendarConnectionDialogProps {
  readonly open: boolean;
  readonly onClose: () => void;
  /** Called after a connection was stored, so the card refreshes its status. */
  readonly onConnected: () => void;
}

type Provider = 'icloud' | 'caldav';

/**
 * Connects an external calendar (iCloud with an app-specific Apple-ID password, or any CalDAV server):
 * credentials first, then the calendar is picked from the list the server reports, then the connection
 * is stored after the backend has checked access. The password is sent once per step and never comes
 * back — the interface never sees it again.
 */
export function CalendarConnectionDialog({ open, onClose, onConnected }: CalendarConnectionDialogProps) {
  const t = useTranslations('integrations');
  const toast = useToast();

  const [provider, setProvider] = useState<Provider>('icloud');
  const [account, setAccount] = useState('');
  const [appPassword, setAppPassword] = useState('');
  const [serverUrl, setServerUrl] = useState('');
  const [calendars, setCalendars] = useState<readonly CalDavCalendarInfo[] | null>(null);
  const [selectedPath, setSelectedPath] = useState<string>('');
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);

  const reset = (): void => {
    setCalendars(null);
    setSelectedPath('');
    setFailure(null);
    setAppPassword('');
  };

  const credentialsPayload = (): {
    provider: Provider;
    account: string;
    appPassword: string;
    serverUrl?: string;
  } => ({
    provider,
    account: account.trim(),
    appPassword,
    serverUrl: provider === 'caldav' ? serverUrl.trim() || undefined : undefined,
  });

  const discover = async (): Promise<void> => {
    setFailure(null);
    if (!account.trim() || !appPassword) {
      setFailure(t('calendarDialog.invalidRequest'));
      return;
    }
    setBusy(true);
    try {
      const found = await api.calendar.connection.discover(credentialsPayload());
      setCalendars(found);
      setSelectedPath(found[0]?.path ?? '');
      if (found.length === 0) setFailure(t('calendarDialog.noneFound'));
    } catch (caught) {
      const error = toApiError(caught);
      setFailure(
        error.kind === 'validation' && error.message ? error.message : t('calendarDialog.failed'),
      );
    } finally {
      setBusy(false);
    }
  };

  const connect = async (): Promise<void> => {
    setFailure(null);
    if (!selectedPath) {
      setFailure(t('calendarDialog.invalidRequest'));
      return;
    }
    setBusy(true);
    try {
      const chosen = calendars?.find((calendar) => calendar.path === selectedPath);
      await api.calendar.connection.connect({
        ...credentialsPayload(),
        calendarPath: selectedPath,
        calendarName: chosen?.name ?? undefined,
      });
      toast.success(t('calendarDialog.connected', { name: chosen?.name ?? selectedPath }));
      reset();
      onConnected();
      onClose();
    } catch (caught) {
      const error = toApiError(caught);
      setFailure(
        error.kind === 'validation' && error.message ? error.message : t('calendarDialog.failed'),
      );
    } finally {
      setBusy(false);
    }
  };

  const close = (): void => {
    reset();
    onClose();
  };

  return (
    <Dialog
      open={open}
      onClose={close}
      title={t('calendarDialog.title')}
      description={t('calendarDialog.intro')}
      size="lg"
    >
      <div style={{ display: 'grid', gap: '1rem' }}>
        {calendars === null ? (
          <>
            <Field label={t('calendarDialog.provider')}>
              <Select
                value={provider}
                onChange={(event) => setProvider(event.target.value as Provider)}
                options={[
                  { value: 'icloud', label: t('calendarDialog.provider.icloud') },
                  { value: 'caldav', label: t('calendarDialog.provider.caldav') },
                ]}
              />
            </Field>
            <Field label={t(provider === 'icloud' ? 'calendarDialog.account' : 'calendarDialog.accountCaldav')}>
              <Input
                type="text"
                value={account}
                autoComplete="username"
                onChange={(event) => setAccount(event.target.value)}
                placeholder={provider === 'icloud' ? 'name@icloud.com' : 'benutzer@beispiel.de'}
              />
            </Field>
            <Field label={t('calendarDialog.appPassword')} hint={t('calendarDialog.appPasswordHint')}>
              <Input
                type="password"
                value={appPassword}
                autoComplete="current-password"
                onChange={(event) => setAppPassword(event.target.value)}
              />
            </Field>
            {provider === 'caldav' ? (
              <Field label={t('calendarDialog.serverUrl')}>
                <Input
                  type="url"
                  value={serverUrl}
                  onChange={(event) => setServerUrl(event.target.value)}
                  placeholder="https://caldav.example.com"
                />
              </Field>
            ) : null}
            <div>
              <Button variant="primary" loading={busy} onClick={() => void discover()}>
                {busy ? t('calendarDialog.discovering') : t('calendarDialog.discover')}
              </Button>
            </div>
          </>
        ) : (
          <>
            <Field label={t('calendarDialog.pickCalendar')}>
              <Select
                value={selectedPath}
                onChange={(event) => setSelectedPath(event.target.value)}
                options={calendars.map((calendar) => ({
                  value: calendar.path,
                  label: calendar.name || calendar.path,
                }))}
              />
            </Field>
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: '0.5rem' }}>
              <Button variant="primary" loading={busy} onClick={() => void connect()}>
                {busy ? t('calendarDialog.connecting') : t('calendarDialog.connect')}
              </Button>
              <Button variant="secondary" disabled={busy} onClick={reset}>
                {t('calendarDialog.back')}
              </Button>
            </div>
          </>
        )}

        {failure ? <InlineAlert tone="danger" title={failure} /> : null}
        <InlineAlert tone="info">{t('calendarDialog.securityNote')}</InlineAlert>
      </div>
    </Dialog>
  );
}
