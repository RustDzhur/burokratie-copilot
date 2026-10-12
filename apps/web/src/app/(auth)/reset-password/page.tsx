'use client';

import Link from 'next/link';
import { useSearchParams } from 'next/navigation';
import { Suspense, useState, type FormEvent } from 'react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/form';
import { InlineAlert } from '@/components/ui/feedback';
import { useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { toApiError, type ApiError } from '@/lib/api/errors';
import styles from '../auth-card.module.css';

const MIN_PASSWORD_LENGTH = 12;

/**
 * New password for a reset link (`/reset-password?token=…`). The token is generated and validated only by
 * the backend; the page just forwards it together with the chosen password.
 */
function ConfirmReset({ token }: { readonly token: string }) {
  const t = useTranslations('auth');
  const tErrors = useTranslations('errors');

  const [password, setPassword] = useState('');
  const [pending, setPending] = useState(false);
  const [done, setDone] = useState(false);
  const [tooShort, setTooShort] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  const onSubmit = async (event: FormEvent<HTMLFormElement>): Promise<void> => {
    event.preventDefault();
    if (password.length < MIN_PASSWORD_LENGTH) {
      setTooShort(true);
      return;
    }
    setTooShort(false);
    setPending(true);
    setError(null);
    try {
      await api.auth.confirmPasswordReset({ token, password });
      setDone(true);
    } catch (caught) {
      setError(toApiError(caught));
    } finally {
      setPending(false);
    }
  };

  return (
    <section className={styles.card} aria-labelledby="reset-title">
      <h1 className={styles.title} id="reset-title">
        {t('reset.confirmTitle')}
      </h1>
      <p className={styles.subtitle}>{t('reset.confirmSubtitle')}</p>

      {error ? <InlineAlert tone="danger" title={tErrors(error.kind)} /> : null}
      {done ? <InlineAlert tone="success" title={t('reset.confirmed')} /> : null}

      {done ? null : (
        <form className={styles.form} onSubmit={(event) => void onSubmit(event)} noValidate>
          <Input
            label={t('reset.passwordLabel')}
            type="password"
            name="new-password"
            autoComplete="new-password"
            required
            error={tooShort ? t('reset.tooShort') : undefined}
            value={password}
            onChange={(event) => setPassword(event.target.value)}
          />
          <Button type="submit" variant="primary" size="lg" fullWidth loading={pending}>
            {t('reset.confirmSubmit')}
          </Button>
        </form>
      )}

      <div className={styles.meta}>
        <Link href="/login">{t('reset.backToLogin')}</Link>
      </div>
    </section>
  );
}

/** Password reset request: asks for the address; the link in the mail leads to {@link ConfirmReset}. */
function RequestReset() {
  const t = useTranslations('auth');
  const tErrors = useTranslations('errors');

  const [email, setEmail] = useState('');
  const [pending, setPending] = useState(false);
  const [sent, setSent] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  const onSubmit = async (event: FormEvent<HTMLFormElement>): Promise<void> => {
    event.preventDefault();
    setPending(true);
    setError(null);
    try {
      await api.auth.requestPasswordReset({ email });
      setSent(true);
    } catch (caught) {
      setError(toApiError(caught));
    } finally {
      setPending(false);
    }
  };

  return (
    <section className={styles.card} aria-labelledby="reset-title">
      <h1 className={styles.title} id="reset-title">
        {t('reset.title')}
      </h1>
      <p className={styles.subtitle}>{t('reset.subtitle')}</p>

      {error ? <InlineAlert tone="danger" title={tErrors(error.kind)} /> : null}
      {sent ? (
        <InlineAlert tone="success" title={t('reset.sent')}>
          {t('reset.newPassword')}
        </InlineAlert>
      ) : null}

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
        <Button type="submit" variant="primary" size="lg" fullWidth loading={pending}>
          {t('reset.submit')}
        </Button>
      </form>

      <div className={styles.meta}>
        <Link href="/login">{t('reset.backToLogin')}</Link>
      </div>
    </section>
  );
}

export default function ResetPasswordPage() {
  return (
    <Suspense fallback={null}>
      <ResetPasswordRouter />
    </Suspense>
  );
}

function ResetPasswordRouter() {
  const token = useSearchParams().get('token');
  return token ? <ConfirmReset token={token} /> : <RequestReset />;
}
