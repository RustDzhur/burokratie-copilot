'use client';

import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useState, type FormEvent } from 'react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/form';
import { InlineAlert } from '@/components/ui/feedback';
import { useToast } from '@/components/ui/toast';
import { useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { toApiError, type ApiError } from '@/lib/api/errors';
import styles from '../auth-card.module.css';

/** E-mail verification with code resend. */
export default function VerifyEmailPage() {
  const t = useTranslations('auth');
  const tErrors = useTranslations('errors');
  const router = useRouter();
  const toast = useToast();

  const [email, setEmail] = useState('');
  const [code, setCode] = useState('');
  const [pending, setPending] = useState(false);
  const [resending, setResending] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  const onSubmit = async (event: FormEvent<HTMLFormElement>): Promise<void> => {
    event.preventDefault();
    setPending(true);
    setError(null);
    try {
      await api.auth.verifyEmail({ email, code });
      router.push('/onboarding');
    } catch (caught) {
      setError(toApiError(caught));
    } finally {
      setPending(false);
    }
  };

  const onResend = async (): Promise<void> => {
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
      <p className={styles.subtitle}>{t('verify.subtitle', { email: email || '…' })}</p>

      {error ? <InlineAlert tone="danger" title={tErrors(error.kind)} /> : null}

      <form className={styles.form} onSubmit={onSubmit} noValidate>
        <Input
          label={t('reset.email')}
          type="email"
          name="email"
          autoComplete="email"
          required
          value={email}
          onChange={(event) => setEmail(event.target.value)}
        />
        <Input
          label={t('verify.code')}
          name="code"
          inputMode="numeric"
          autoComplete="one-time-code"
          required
          value={code}
          onChange={(event) => setCode(event.target.value)}
        />
        <Button type="submit" variant="primary" size="lg" fullWidth loading={pending}>
          {t('verify.submit')}
        </Button>
      </form>

      <p className={styles.hint}>{t('verify.hint')}</p>

      <div className={styles.meta}>
        <Button
          variant="secondary"
          onClick={() => void onResend()}
          loading={resending}
          disabled={email === ''}
        >
          {t('verify.resend')}
        </Button>
        <Link href="/login">{t('reset.backToLogin')}</Link>
      </div>
    </section>
  );
}
