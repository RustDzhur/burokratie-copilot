'use client';

import Link from 'next/link';
import { useState, type FormEvent } from 'react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/form';
import { InlineAlert } from '@/components/ui/feedback';
import { useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { toApiError, type ApiError } from '@/lib/api/errors';
import styles from '../auth-card.module.css';

/**
 * Password reset request.
 *
 * Only the request step lives in the client: the reset link containing the
 * one-time token is generated and validated exclusively by the backend.
 */
export default function ResetPasswordPage() {
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
