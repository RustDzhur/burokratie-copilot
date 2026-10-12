'use client';

import Link from 'next/link';
import { useSearchParams } from 'next/navigation';
import { Suspense, useEffect, useState, type FormEvent } from 'react';
import { Button } from '@/components/ui/button';
import { buttonClass } from '@/components/ui/button-class';
import { Input } from '@/components/ui/form';
import { InlineAlert } from '@/components/ui/feedback';
import { useToast } from '@/components/ui/toast';
import { useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { toApiError, type ApiError } from '@/lib/api/errors';
import styles from '../auth-card.module.css';

type Outcome = 'idle' | 'working' | 'done' | 'failed';

/**
 * E-mail verification. The mail carries a link `/verify-email?token=…`; opening it confirms the address.
 * Without a token the page only tells the user to check the mailbox and offers to send the link again.
 */
function VerifyEmail() {
  const t = useTranslations('auth');
  const tErrors = useTranslations('errors');
  const toast = useToast();
  const token = useSearchParams().get('token');

  const [outcome, setOutcome] = useState<Outcome>(token ? 'working' : 'idle');
  const [email, setEmail] = useState('');
  const [resending, setResending] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  useEffect(() => {
    if (!token) return;
    let cancelled = false;
    api.auth
      .verifyEmail({ token })
      .then(() => {
        if (!cancelled) setOutcome('done');
      })
      .catch((caught: unknown) => {
        if (cancelled) return;
        setError(toApiError(caught));
        setOutcome('failed');
      });
    return () => {
      cancelled = true;
    };
  }, [token]);

  const onResend = async (event: FormEvent<HTMLFormElement>): Promise<void> => {
    event.preventDefault();
    setResending(true);
    setError(null);
    try {
      await api.auth.resendVerification({ email });
      toast.success(t('verify.resent'));
    } catch (caught) {
      setError(toApiError(caught));
    } finally {
      setResending(false);
    }
  };

  return (
    <section className={styles.card} aria-labelledby="verify-title">
      <h1 className={styles.title} id="verify-title">
        {t('verify.title')}
      </h1>

      {outcome === 'working' ? <p className={styles.subtitle}>{t('verify.working')}</p> : null}
      {outcome === 'idle' ? <p className={styles.subtitle}>{t('verify.checkMail')}</p> : null}
      {outcome === 'done' ? (
        <>
          <InlineAlert tone="success" title={t('verify.done')} />
          <Link href="/login" className={buttonClass({ variant: 'primary', size: 'lg', fullWidth: true })}>
            {t('verify.goToLogin')}
          </Link>
        </>
      ) : null}

      {outcome === 'failed' ? <InlineAlert tone="danger" title={t('verify.failed')} /> : null}
      {error && outcome !== 'failed' ? <InlineAlert tone="danger" title={tErrors(error.kind)} /> : null}

      {outcome === 'idle' || outcome === 'failed' ? (
        <form className={styles.form} onSubmit={(event) => void onResend(event)} noValidate>
          <Input
            label={t('reset.email')}
            type="email"
            name="email"
            autoComplete="email"
            required
            value={email}
            onChange={(event) => setEmail(event.target.value)}
          />
          <Button type="submit" variant="secondary" loading={resending} disabled={email === ''}>
            {t('verify.resend')}
          </Button>
        </form>
      ) : null}

      <p className={styles.hint}>{t('verify.hint')}</p>
      <div className={styles.meta}>
        <Link href="/login">{t('reset.backToLogin')}</Link>
      </div>
    </section>
  );
}

export default function VerifyEmailPage() {
  // useSearchParams needs a Suspense boundary for static prerendering.
  return (
    <Suspense fallback={null}>
      <VerifyEmail />
    </Suspense>
  );
}
