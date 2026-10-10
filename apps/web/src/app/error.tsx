'use client';

import { useEffect } from 'react';
import { Button } from '@/components/ui/button';
import { InlineAlert } from '@/components/ui/feedback';
import { useTranslations } from '@/i18n/provider';
import styles from './status-page.module.css';

/**
 * Route-level error boundary. Renders the shared error state with a retry action
 * and never exposes stack traces or internal identifiers to users.
 */
export default function GlobalError({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  const t = useTranslations('states');
  const tCommon = useTranslations('common');

  useEffect(() => {
    console.error('[app] unhandled route error', error.message);
  }, [error]);

  return (
    <main className={styles.page} id="main-content">
      <h1 className={styles.title}>{t('error.title')}</h1>
      <InlineAlert tone="danger" title={t('error.summary')}>
        {t('error.hint')}
      </InlineAlert>
      <Button variant="primary" iconLeft="refresh" onClick={reset}>
        {tCommon('actions.retry')}
      </Button>
    </main>
  );
}
