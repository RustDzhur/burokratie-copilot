'use client';

import { useEffect, useState } from 'react';
import { Select, Switch } from '@/components/ui';
import { Button } from '@/components/ui/button';
import { InlineAlert, Spinner } from '@/components/ui/feedback';
import { Dialog } from '@/components/ui/overlay';
import { useToast } from '@/components/ui/toast';
import { useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { toApiError } from '@/lib/api/errors';
import type { NotificationPreferences } from '@/lib/api/types';

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
const CHANNELS = ['in_app', 'email', 'push'] as const;
const FREQUENCIES = ['immediate', 'digest', 'off'] as const;

export interface NotificationPreferencesDialogProps {
  readonly open: boolean;
  readonly onClose: () => void;
}

/** Frequency per category and an on/off per channel; saved in one step. */
export function NotificationPreferencesDialog({ open, onClose }: NotificationPreferencesDialogProps) {
  const t = useTranslations('notifications');
  const tCommon = useTranslations('common');
  const tErrors = useTranslations('errors');
  const toast = useToast();

  const [preferences, setPreferences] = useState<NotificationPreferences | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (!open) return;
    const controller = new AbortController();
    setError(null);
    api.notifications
      .preferences(controller.signal)
      .then(setPreferences)
      .catch((caught: unknown) => {
        if (!controller.signal.aborted) setError(tErrors(toApiError(caught).kind));
      });
    return () => controller.abort();
  }, [open, tErrors]);

  const save = async (): Promise<void> => {
    if (!preferences) return;
    setSaving(true);
    setError(null);
    try {
      await api.notifications.savePreferences(preferences);
      toast.success(t('action.settings'));
      onClose();
    } catch (caught) {
      setError(tErrors(toApiError(caught).kind));
    } finally {
      setSaving(false);
    }
  };

  return (
    <Dialog
      open={open}
      onClose={onClose}
      title={t('action.settings')}
      description={t('privacy.notice')}
      footer={
        <>
          <Button variant="ghost" onClick={onClose} disabled={saving}>
            {tCommon('actions.cancel')}
          </Button>
          <Button variant="primary" onClick={() => void save()} loading={saving} disabled={!preferences}>
            {tCommon('actions.save')}
          </Button>
        </>
      }
    >
      {error ? <InlineAlert tone="danger" title={error} /> : null}
      {preferences ? (
        <>
          {CATEGORIES.map((category) => (
            <Select
              key={category}
              label={`${t(`category.${category}` as 'category.task')} – ${t('frequency.label')}`}
              value={preferences.categories[category]}
              options={FREQUENCIES.map((value) => ({
                value,
                label: t(`frequency.${value}` as 'frequency.immediate'),
              }))}
              onChange={(event) =>
                setPreferences({
                  ...preferences,
                  categories: {
                    ...preferences.categories,
                    [category]: event.target.value as NotificationPreferences['categories'][typeof category],
                  },
                })
              }
            />
          ))}
          {CHANNELS.map((channel) => (
            <Switch
              key={channel}
              label={t(`channel.${channel === 'in_app' ? 'inApp' : channel}` as 'channel.inApp')}
              checked={preferences.channels[channel]}
              onChange={(checked) =>
                setPreferences({
                  ...preferences,
                  channels: { ...preferences.channels, [channel]: checked },
                })
              }
            />
          ))}
        </>
      ) : error ? null : (
        <Spinner />
      )}
    </Dialog>
  );
}
