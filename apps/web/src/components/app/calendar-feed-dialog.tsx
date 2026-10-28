'use client';

import { useState } from 'react';
import { Button } from '@/components/ui/button';
import { InlineAlert, SkeletonText } from '@/components/ui/feedback';
import { Dialog } from '@/components/ui/overlay';
import { useToast } from '@/components/ui/toast';
import { useLocale, useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { useApiResource } from '@/lib/api/hooks';

interface CalendarFeedDialogProps {
  readonly open: boolean;
  readonly onClose: () => void;
}

interface FreshFeed {
  readonly label: string;
  readonly httpsUrl: string;
  readonly webcalUrl: string;
}

/**
 * Connects the calendar of the phone: a private address that the iPhone Calendar (or an Android calendar app) subscribes
 * to. The address is shown once, when it is created, because it works as a password: whoever has it can read the
 * calendar. It can be revoked at any time, which stops it working at once.
 */
export function CalendarFeedDialog({ open, onClose }: CalendarFeedDialogProps) {
  const t = useTranslations('calendar');
  const toast = useToast();
  const locale = useLocale();
  const [reloadKey, setReloadKey] = useState(0);
  const [label, setLabel] = useState('');
  const [busy, setBusy] = useState(false);
  const [fresh, setFresh] = useState<FreshFeed | null>(null);

  const { state } = useApiResource((signal) => api.calendar.feeds.list(signal), [reloadKey, open], {
    skip: !open,
  });

  const create = async (): Promise<void> => {
    setBusy(true);
    try {
      const created = await api.calendar.feeds.create(
        label.trim() || t('feed.defaultLabel'),
        locale,
      );
      const httpsUrl = `${window.location.origin}/api/v1/calendar/feed/${created.token}.ics`;
      setFresh({
        label: created.label,
        httpsUrl,
        webcalUrl: httpsUrl.replace(/^https?:/, 'webcal:'),
      });
      setLabel('');
      setReloadKey((value) => value + 1);
    } catch {
      toast.error(t('feed.failed'));
    } finally {
      setBusy(false);
    }
  };

  const revoke = async (id: string): Promise<void> => {
    try {
      await api.calendar.feeds.revoke(id);
      toast.success(t('feed.revoked'));
      setFresh(null);
      setReloadKey((value) => value + 1);
    } catch {
      toast.error(t('feed.failed'));
    }
  };

  const copy = async (value: string): Promise<void> => {
    try {
      await navigator.clipboard.writeText(value);
      toast.success(t('feed.copied'));
    } catch {
      toast.error(t('feed.copyFailed'));
    }
  };

  return (
    <Dialog
      open={open}
      onClose={onClose}
      title={t('feed.title')}
      description={t('feed.intro')}
      size="lg"
    >
      <div style={{ display: 'grid', gap: '1rem' }}>
        <ul style={{ margin: 0, paddingInlineStart: '1.25rem' }}>
          <li>{t('feed.point.appears')}</li>
          <li>{t('feed.point.rings')}</li>
          <li>{t('feed.point.detail')}</li>
        </ul>

        {fresh ? (
          <InlineAlert tone="success" title={t('feed.created', { label: fresh.label })}>
            <p style={{ margin: 0 }}>{t('feed.shownOnce')}</p>
            <p style={{ overflowWrap: 'anywhere', fontFamily: 'monospace', fontSize: '0.8rem' }}>
              {fresh.webcalUrl}
            </p>
            <p style={{ display: 'flex', flexWrap: 'wrap', gap: '0.5rem', margin: 0 }}>
              <a href={fresh.webcalUrl}>{t('feed.openOnIphone')}</a>
              <Button variant="secondary" size="sm" onClick={() => void copy(fresh.httpsUrl)}>
                {t('feed.copy')}
              </Button>
            </p>
          </InlineAlert>
        ) : null}

        <div style={{ display: 'flex', flexWrap: 'wrap', gap: '0.5rem', alignItems: 'end' }}>
          <label style={{ display: 'grid', gap: '0.25rem' }}>
            <span>{t('feed.labelField')}</span>
            <input
              type="text"
              value={label}
              maxLength={100}
              placeholder={t('feed.defaultLabel')}
              onChange={(event) => setLabel(event.target.value)}
              style={{ padding: '0.5rem', font: 'inherit' }}
            />
          </label>
          <Button variant="primary" loading={busy} onClick={() => void create()}>
            {t('feed.create')}
          </Button>
        </div>

        <details>
          <summary>{t('feed.iphone.title')}</summary>
          <ol>
            <li>{t('feed.iphone.step1')}</li>
            <li>{t('feed.iphone.step2')}</li>
            <li>{t('feed.iphone.step3')}</li>
          </ol>
        </details>
        <details>
          <summary>{t('feed.android.title')}</summary>
          <ol>
            <li>{t('feed.android.step1')}</li>
            <li>{t('feed.android.step2')}</li>
            <li>{t('feed.android.step3')}</li>
          </ol>
          <p style={{ fontSize: '0.875rem' }}>{t('feed.android.google')}</p>
        </details>

        <div>
          <strong>{t('feed.existing')}</strong>
          {state.status === 'loading' ? (
            <SkeletonText lines={2} />
          ) : state.status === 'ready' && state.data.length > 0 ? (
            <ul style={{ listStyle: 'none', padding: 0, display: 'grid', gap: '0.5rem' }}>
              {state.data.map((feed) => (
                <li
                  key={feed.id}
                  style={{
                    display: 'flex',
                    gap: '0.75rem',
                    alignItems: 'center',
                    flexWrap: 'wrap',
                  }}
                >
                  <span>{feed.label}</span>
                  <span style={{ fontSize: '0.8rem', opacity: 0.7 }}>
                    {feed.lastUsedAt
                      ? t('feed.lastUsed', { date: feed.lastUsedAt.slice(0, 16).replace('T', ' ') })
                      : t('feed.neverUsed')}
                  </span>
                  <Button variant="ghost" size="sm" onClick={() => void revoke(feed.id)}>
                    {t('feed.revoke')}
                  </Button>
                </li>
              ))}
            </ul>
          ) : (
            <p>{t('feed.none')}</p>
          )}
        </div>

        <InlineAlert tone="warning">{t('feed.security')}</InlineAlert>
      </div>
    </Dialog>
  );
}
