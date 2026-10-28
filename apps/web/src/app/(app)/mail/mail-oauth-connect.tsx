'use client';

import { useEffect, useState } from 'react';
import { InlineAlert, Spinner } from '@/components/ui/feedback';
import { useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import type { MailOAuthProviderSummary } from '@/lib/api/types';
import styles from './mail-oauth-connect.module.css';

type Outcome =
  | { readonly kind: 'connected'; readonly provider: string }
  | { readonly kind: 'error'; readonly reason: string };

/**
 * The two OAuth buttons of the mail screen.
 *
 * <p>An OAuth connection cannot be done in the browser: the browser must be sent to the provider with a
 * state and a PKCE challenge, and the provider must send it back. The buttons are therefore ordinary links
 * to the backend's {@code /start} route, which answers with a redirect.</p>
 *
 * <p>The screen asks first which providers this installation can actually offer. A fresh installation has
 * registered no application with Microsoft or Google, and a button that led into a flow that cannot finish
 * would be worse than a sentence explaining what the operator has to do. The client secret never reaches
 * this component; it only sees whether a provider is configured and which settings are missing.</p>
 */
export function MailOAuthConnect() {
  const t = useTranslations('mail');
  const [providers, setProviders] = useState<readonly MailOAuthProviderSummary[] | null>(null);
  const [failed, setFailed] = useState(false);
  const [outcome, setOutcome] = useState<Outcome | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    api.mailOAuth
      .providers(controller.signal)
      .then(setProviders)
      .catch(() => {
        if (!controller.signal.aborted) setFailed(true);
      });
    return () => controller.abort();
  }, []);

  // The provider sends the browser back to this page with the outcome. Read it from the location instead of
  // useSearchParams so the page does not need a Suspense boundary for a purely client-side message.
  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const flag = params.get('oauth');
    if (flag === 'connected') {
      setOutcome({ kind: 'connected', provider: params.get('provider') ?? '' });
    } else if (flag === 'error') {
      setOutcome({ kind: 'error', reason: params.get('reason') ?? 'unknown' });
    }
  }, []);

  const providerName = (provider: string): string =>
    provider === 'google' ? t('oauth.googleName') : t('oauth.microsoftName');

  const errorText = (reason: string): string => {
    switch (reason) {
      case 'state':
        return t('oauth.error.state');
      case 'denied':
        return t('oauth.error.denied');
      case 'code':
        return t('oauth.error.code');
      case 'exchange':
        return t('oauth.error.exchange');
      case 'userinfo':
        return t('oauth.error.userinfo');
      case 'storage':
      case 'unavailable':
        return t('oauth.error.unavailable');
      default:
        return t('oauth.error.unknown');
    }
  };

  return (
    <section className={styles.section} aria-labelledby="mail-oauth-heading">
      <h2 id="mail-oauth-heading" className={styles.title}>
        {t('oauth.title')}
      </h2>
      <p className={styles.description}>{t('oauth.description')}</p>

      {outcome?.kind === 'connected' ? (
        <InlineAlert tone="success">
          {t('oauth.connected', { provider: providerName(outcome.provider) })}
        </InlineAlert>
      ) : null}
      {outcome?.kind === 'error' ? (
        <InlineAlert tone="warning" title={t('oauth.errorTitle')}>
          {errorText(outcome.reason)}
        </InlineAlert>
      ) : null}

      {failed ? (
        <InlineAlert tone="warning">{t('oauth.loadFailed')}</InlineAlert>
      ) : providers === null ? (
        <Spinner />
      ) : (
        <div className={styles.buttons}>
          {providers.map((provider) =>
            provider.configured ? (
              <a
                key={provider.provider}
                className={styles.connect}
                href={api.mailOAuth.startPath(provider.provider)}
              >
                {t('oauth.connect', { provider: providerName(provider.provider) })}
              </a>
            ) : (
              <span key={provider.provider} className={styles.unavailable}>
                {t('oauth.connect', { provider: providerName(provider.provider) })}
              </span>
            ),
          )}
        </div>
      )}

      {providers !== null && providers.some((provider) => !provider.configured) ? (
        <p className={styles.note}>{t('oauth.notConfigured')}</p>
      ) : null}
      <p className={styles.note}>{t('notConnected.description')}</p>
    </section>
  );
}
