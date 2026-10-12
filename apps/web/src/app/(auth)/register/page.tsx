'use client';

import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useState, type FormEvent } from 'react';
import { Button } from '@/components/ui/button';
import { Checkbox, Input, RadioGroup } from '@/components/ui/form';
import { InlineAlert } from '@/components/ui/feedback';
import { useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { toApiError, type ApiError } from '@/lib/api/errors';
import styles from '../auth-card.module.css';

const MIN_PASSWORD_LENGTH = 12;

type AccountType = 'private' | 'family' | 'company';

/**
 * Registration screen.
 *
 * Password strength and email verification are server-side concerns
 * (docs/task.md §21.3). The client only checks that both password fields match
 * so users are not sent on a pointless round trip.
 */
export default function RegisterPage() {
  const t = useTranslations('auth');
  const tErrors = useTranslations('errors');
  const tCommon = useTranslations('common');
  const router = useRouter();

  const [fullName, setFullName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [passwordConfirm, setPasswordConfirm] = useState('');
  const [accountType, setAccountType] = useState<AccountType>('private');
  const [terms, setTerms] = useState(false);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [mismatch, setMismatch] = useState(false);
  const [tooShort, setTooShort] = useState(false);

  const onSubmit = async (event: FormEvent<HTMLFormElement>): Promise<void> => {
    event.preventDefault();
    // The backend enforces the same minimum (AuthController.RegisterRequest); checking here saves the trip.
    if (password.length < MIN_PASSWORD_LENGTH) {
      setTooShort(true);
      return;
    }
    setTooShort(false);
    if (password !== passwordConfirm) {
      setMismatch(true);
      return;
    }
    setMismatch(false);
    setPending(true);
    setError(null);
    try {
      await api.auth.register({ fullName, email, password, accountType });
      router.push('/verify-email');
    } catch (caught) {
      setError(toApiError(caught));
    } finally {
      setPending(false);
    }
  };

  return (
    <section className={styles.card} aria-labelledby="register-title">
      <h1 className={styles.title} id="register-title">
        {t('register.title')}
      </h1>
      <p className={styles.subtitle}>{t('register.subtitle')}</p>

      {error ? (
        <InlineAlert tone="danger" title={tErrors(error.kind)}>
          {error.fieldErrors
            ? Object.values(error.fieldErrors).join(' · ')
            : tCommon('labels.unknownError')}
        </InlineAlert>
      ) : null}

      <form className={styles.form} onSubmit={onSubmit} noValidate>
        <Input
          label={t('register.fullName')}
          name="fullName"
          autoComplete="name"
          required
          value={fullName}
          onChange={(event) => setFullName(event.target.value)}
        />
        <Input
          label={t('register.email')}
          type="email"
          name="email"
          autoComplete="email"
          required
          value={email}
          onChange={(event) => setEmail(event.target.value)}
        />
        <Input
          label={t('register.password')}
          type="password"
          name="new-password"
          autoComplete="new-password"
          required
          hint={t('register.passwordHelp')}
          error={tooShort ? t('register.passwordTooShort') : undefined}
          value={password}
          onChange={(event) => setPassword(event.target.value)}
        />
        <Input
          label={t('register.passwordConfirm')}
          type="password"
          name="password-confirm"
          autoComplete="new-password"
          required
          error={mismatch ? t('register.mismatch') : undefined}
          value={passwordConfirm}
          onChange={(event) => setPasswordConfirm(event.target.value)}
        />
        <RadioGroup
          legend={t('register.accountType')}
          name="accountType"
          value={accountType}
          onChange={(value) => setAccountType(value as AccountType)}
          options={[
            { value: 'private', label: t('register.typePrivate') },
            { value: 'family', label: t('register.typeFamily') },
            { value: 'company', label: t('register.typeBusiness') },
          ]}
        />
        <Checkbox
          label={t('register.terms')}
          checked={terms}
          required
          onChange={(event) => setTerms(event.target.checked)}
        />
        <Button
          type="submit"
          variant="primary"
          size="lg"
          fullWidth
          loading={pending}
          disabled={!terms}
        >
          {t('register.submit')}
        </Button>
      </form>

      <div className={styles.meta}>
        <span>
          {t('register.hasAccount')} <Link href="/login">{t('register.login')}</Link>
        </span>
      </div>
    </section>
  );
}
