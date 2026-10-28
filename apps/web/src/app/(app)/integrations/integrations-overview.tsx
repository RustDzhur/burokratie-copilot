'use client';

import { useCallback, useEffect, useState } from 'react';
import { Card, CardBody, CardHeader, StatusPill } from '@/components/ui';
import { Button } from '@/components/ui/button';
import { InlineAlert, Spinner } from '@/components/ui/feedback';
import { useToast } from '@/components/ui/toast';
import { formatDate } from '@/i18n/format';
import { useLocale, useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { toApiError } from '@/lib/api/errors';
import type { IntegrationCatalogueEntry, MailAccountSummary } from '@/lib/api/types';
import styles from './integrations.module.css';

const MAILBOX_STATUS = ['CONNECTED', 'DISCONNECTED', 'ERROR'] as const;

/** `reloadKey` changes whenever a mailbox was connected, so the list below refreshes itself. */
export function IntegrationsOverview({ reloadKey }: { readonly reloadKey: number }) {
  const t = useTranslations('integrations');
  const tErrors = useTranslations('errors');
  const locale = useLocale();
  const toast = useToast();

  const [accounts, setAccounts] = useState<readonly MailAccountSummary[] | null>(null);
  const [catalogue, setCatalogue] = useState<readonly IntegrationCatalogueEntry[] | null>(null);
  const [catalogueFailed, setCatalogueFailed] = useState(false);
  const [busy, setBusy] = useState<string | null>(null);
  const [version, setVersion] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    api.mailAccounts
      .list(controller.signal)
      .then(setAccounts)
      .catch(() => {
        if (!controller.signal.aborted) setAccounts([]);
      });
    return () => controller.abort();
  }, [reloadKey, version]);

  useEffect(() => {
    const controller = new AbortController();
    api.integrations
      .catalogue(controller.signal)
      .then(setCatalogue)
      .catch(() => {
        if (!controller.signal.aborted) setCatalogueFailed(true);
      });
    return () => controller.abort();
  }, []);

  const collect = useCallback(
    async (account: MailAccountSummary): Promise<void> => {
      setBusy(account.id);
      try {
        const result = await api.mailAccounts.collect(account.id);
        toast.success(
          account.email,
          t('mailbox.collected', {
            stored: result.stored,
            skipped: result.skipped,
            documents: result.documentsCreated,
          }),
        );
      } catch (caught) {
        toast.error(account.email, tErrors(toApiError(caught).kind));
      } finally {
        setBusy(null);
        setVersion((current) => current + 1);
      }
    },
    [t, tErrors, toast],
  );

  const available = (catalogue ?? []).filter((entry) => entry.implemented);
  const unavailable = (catalogue ?? []).filter((entry) => !entry.implemented);

  return (
    <>
      <section className={styles.section} aria-labelledby="mailboxes-heading">
        <Card>
          <CardHeader
            title={t('mailbox.title')}
            description={t('mailbox.description')}
            titleId="mailboxes-heading"
            headingLevel={2}
          />
          <CardBody>
            {accounts === null ? (
              <Spinner />
            ) : accounts.length === 0 ? (
              <p className={styles.meta}>{t('mailbox.empty')}</p>
            ) : (
              <ul className={styles.list}>
                {accounts.map((account) => (
                  <li key={account.id} className={styles.row}>
                    <div>
                      <strong>{account.email}</strong>
                      <p className={styles.meta}>
                        {account.lastSyncAt
                          ? t('mailbox.lastSync', { date: formatDate(account.lastSyncAt, locale) })
                          : t('mailbox.neverSynced')}
                      </p>
                      {account.lastError ? (
                        <p className={`${styles.meta} ${styles.error}`}>{account.lastError}</p>
                      ) : null}
                    </div>
                    <StatusPill
                      label={
                        (MAILBOX_STATUS as readonly string[]).includes(account.status)
                          ? t(`mailbox.status.${account.status}` as 'mailbox.status.CONNECTED')
                          : account.status
                      }
                      status={account.status === 'CONNECTED' ? 'connected' : account.status === 'ERROR' ? 'error' : 'pending'}
                    />
                    <Button
                      variant="secondary"
                      size="sm"
                      onClick={() => void collect(account)}
                      loading={busy === account.id}
                    >
                      {t('mailbox.collect')}
                    </Button>
                  </li>
                ))}
              </ul>
            )}
          </CardBody>
        </Card>
      </section>

      <section className={styles.section} aria-labelledby="catalogue-heading">
        <h2 id="catalogue-heading">{t('catalogue.title')}</h2>
        <p className={styles.meta}>{t('catalogue.description')}</p>
        {catalogueFailed ? (
          <InlineAlert tone="warning" title={t('catalogue.loadFailed')} />
        ) : catalogue === null ? (
          <Spinner />
        ) : (
          <>
            <div className={styles.cards}>
              {available.map((entry) => (
                <CatalogueCard key={entry.provider} entry={entry} />
              ))}
            </div>
            {unavailable.length > 0 ? (
              <details className={styles.details}>
                <summary>{t('catalogue.unavailable', { count: unavailable.length })}</summary>
                <div className={styles.cards}>
                  {unavailable.map((entry) => (
                    <CatalogueCard key={entry.provider} entry={entry} />
                  ))}
                </div>
              </details>
            ) : null}
          </>
        )}
      </section>
    </>
  );

}

function CatalogueCard({ entry }: { readonly entry: IntegrationCatalogueEntry }) {
  const t = useTranslations('integrations');
  return (
    <Card>
      <CardBody>
        <h3 className={styles.cardTitle}>{entry.name}</h3>
        {entry.capabilities.length > 0 ? (
          <>
            <p className={styles.cardLabel}>{t('catalogue.capabilities')}</p>
            <ul className={styles.cardList}>
              {entry.capabilities.map((item) => (
                <li key={item}>{item}</li>
              ))}
            </ul>
          </>
        ) : null}
        {entry.ownerRequirements.length > 0 ? (
          <>
            <p className={styles.cardLabel}>{t('catalogue.requirements')}</p>
            <ul className={styles.cardList}>
              {entry.ownerRequirements.map((item) => (
                <li key={item}>{item}</li>
              ))}
            </ul>
          </>
        ) : null}
        {entry.note ? <p className={styles.note}>{entry.note}</p> : null}
      </CardBody>
    </Card>
  );
}
