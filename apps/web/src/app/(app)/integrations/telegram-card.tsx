'use client';

import { useCallback, useEffect, useState } from 'react';
import { Card, CardBody, CardHeader } from '@/components/ui';
import { Button } from '@/components/ui/button';
import { buttonClass } from '@/components/ui/button-class';
import { InlineAlert, Spinner } from '@/components/ui/feedback';
import { Select } from '@/components/ui/form';
import { useToast } from '@/components/ui/toast';
import { useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { toApiError } from '@/lib/api/errors';
import type { TelegramStatus } from '@/lib/api/types';
import styles from './integrations.module.css';

/**
 * The Telegram informer: the platform writes to the person's Telegram what arrives, how it was processed, what is
 * important and where it was filed. The person links their own chat with a one-time code.
 */
export function TelegramCard() {
  const t = useTranslations('integrations');
  const tErrors = useTranslations('errors');
  const toast = useToast();
  const [status, setStatus] = useState<TelegramStatus | null>(null);
  const [failed, setFailed] = useState(false);
  const [busy, setBusy] = useState(false);

  const load = useCallback((): void => {
    api.telegram
      .status()
      .then(setStatus)
      .catch(() => setFailed(true));
  }, []);

  useEffect(load, [load]);

  // While a link is waiting to be opened, look every few seconds whether the person has pressed Start.
  const waiting = status !== null && !status.linked && status.deepLink !== null;
  useEffect(() => {
    if (!waiting) return undefined;
    const timer = window.setInterval(load, 3000);
    return () => window.clearInterval(timer);
  }, [waiting, load]);

  const run = async (action: () => Promise<unknown>, done?: string): Promise<void> => {
    setBusy(true);
    try {
      await action();
      if (done) toast.success(done);
      load();
    } catch (error) {
      toast.error(tErrors(toApiError(error).kind));
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className={styles.section} aria-labelledby="telegram-heading">
      <Card>
        <CardHeader
          title={t('telegram.title')}
          description={t('telegram.description')}
          titleId="telegram-heading"
          headingLevel={2}
        />
        <CardBody>
          {failed ? (
            <InlineAlert tone="warning">{t('telegram.loadFailed')}</InlineAlert>
          ) : status === null ? (
            <Spinner />
          ) : !status.configured ? (
            <>
              <InlineAlert tone="info">{t('telegram.notConfigured')}</InlineAlert>
              <ol className={styles.meta}>
                <li>{t('telegram.setup.1')}</li>
                <li>{t('telegram.setup.2')}</li>
                <li>{t('telegram.setup.3')}</li>
              </ol>
            </>
          ) : status.linked ? (
            <div style={{ display: 'grid', gap: '0.75rem' }}>
              <InlineAlert tone="success">{t('telegram.linked', { bot: status.botUsername })}</InlineAlert>
              <Select
                label={t('telegram.level')}
                options={[
                  { value: 'ALL', label: t('telegram.level.ALL') },
                  { value: 'IMPORTANT', label: t('telegram.level.IMPORTANT') },
                ]}
                value={status.level}
                onChange={(event) =>
                  void run(() => api.telegram.setPreferences({ level: event.target.value as TelegramStatus['level'] }))
                }
              />
              <div className={styles.row}>
                <Button
                  variant="secondary"
                  loading={busy}
                  onClick={() => void run(() => api.telegram.setPreferences({ enabled: !status.enabled }))}
                >
                  {status.enabled ? t('telegram.pause') : t('telegram.resume')}
                </Button>
                <Button variant="secondary" loading={busy} onClick={() => void run(() => api.telegram.test(), t('telegram.testSent'))}>
                  {t('telegram.test')}
                </Button>
                <Button variant="ghost" loading={busy} onClick={() => void run(() => api.telegram.unlink())}>
                  {t('telegram.unlink')}
                </Button>
              </div>
              {status.enabled ? null : <p className={styles.meta}>{t('telegram.paused')}</p>}
            </div>
          ) : status.deepLink ? (
            <div style={{ display: 'grid', gap: '0.75rem' }}>
              <p className={styles.meta}>{t('telegram.openHint')}</p>
              <a href={status.deepLink} target="_blank" rel="noopener noreferrer" className={buttonClass({ variant: 'primary' })}>
                {t('telegram.open')}
              </a>
              <p className={styles.meta}>{t('telegram.waiting')}</p>
            </div>
          ) : (
            <Button variant="primary" loading={busy} onClick={() => void run(() => api.telegram.createLinkCode())}>
              {t('telegram.connect')}
            </Button>
          )}
        </CardBody>
      </Card>
    </section>
  );
}
