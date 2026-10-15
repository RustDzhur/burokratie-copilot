'use client';

import Link from 'next/link';
import { useRouter, useSearchParams } from 'next/navigation';
import { Suspense, useEffect, useState, type FormEvent } from 'react';
import { Button } from '@/components/ui/button';
import { Checkbox, Input } from '@/components/ui/form';
import { InlineAlert } from '@/components/ui/feedback';
import { useToast } from '@/components/ui/toast';
import { useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { safeNextPath } from '@/lib/auth/safe-next';
import { toApiError, type ApiError } from '@/lib/api/errors';
import styles from '../auth-card.module.css';

/**
 * Sign-in screen. Errors are mapped from the API envelope (401/403/429/5xx) to
 * localized messages; the raw backend text is never shown to users.
 */
function LoginForm() {
  const t = useTranslations('auth');
  const tCommon = useTranslations('common');
  const tErrors = useTranslations('errors');
  const router = useRouter();
  const toast = useToast();
  const params = useSearchParams();
  const next = safeNextPath(params.get('next'));
  const ssoFailed = params.get('error') === 'oidc';
  const [sso, setSso] = useState(false);
  useEffect(() => {
    let active = true;
    void api.auth.ssoAvailable().then((enabled) => {
      if (active) setSso(enabled);
    });
    return () => {
      active = false;
    };
  }, []);

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [rememberMe, setRememberMe] = useState(true);
  const [pending, setPending] = useState(false);
  const [needsCode, setNeedsCode] = useState(false);
  const [code, setCode] = useState('');
  const [error, setError] = useState<ApiError | null>(null);

  const onSubmit = async (event: FormEvent<HTMLFormElement>): Promise<void> => {
    event.preventDefault();
    setPending(true);
    setError(null);
    try {
      const outcome = needsCode
        ? await api.auth.verifyMfa(code)
        : await api.auth.login({ email, password, rememberMe });
      if ('mfaRequired' in outcome) {
        setNeedsCode(true);
        return;
      }
      const user = outcome;
      toast.success(t('login.title'), tCommon('app.name'));
      // A person who has not been through the first-run wizard goes there before anything else.
      router.push(
        user.mfaEnrolmentRequired
          ? '/security'
          : user.onboardingCompleted === false
            ? '/onboarding'
            : (next ?? '/dashboard'),
      );
    } catch (caught) {
      setError(toApiError(caught));
    } finally {
      setPending(false);
    }
  };

  return (
    <section className={styles.card} aria-labelledby="login-title">
      <h1 className={styles.title} id="login-title">
        {t('login.title')}
      </h1>
      <p className={styles.subtitle}>{t('login.subtitle')}</p>

      {error ? <InlineAlert tone="danger" title={tErrors(error.kind)} /> : null}
      {ssoFailed ? <InlineAlert tone="danger" title={t('login.ssoFailed')} /> : null}

      <form className={styles.form} onSubmit={onSubmit} noValidate>
        {needsCode ? (
          <>
            <p className={styles.subtitle}>{t('login.mfaPrompt')}</p>
            <Input
              label={t('login.mfaCode')}
              name="code"
              inputMode="numeric"
              autoComplete="one-time-code"
              required
              value={code}
              onChange={(event) => setCode(event.target.value)}
            />
          </>
        ) : null}
        {needsCode ? null : (
          <>
            <Input
              label={t('login.email')}
              type="email"
              name="email"
              autoComplete="email"
              required
              value={email}
              onChange={(event) => setEmail(event.target.value)}
            />
            <Input
              label={t('login.password')}
              type="password"
              name="password"
              autoComplete="current-password"
              required
              value={password}
              onChange={(event) => setPassword(event.target.value)}
            />
          </>
        )}
        <div className={styles.row}>
          <Checkbox
            label={t('login.rememberMe')}
            checked={rememberMe}
            onChange={(event) => setRememberMe(event.target.checked)}
          />
          <Link href="/reset-password">{t('login.forgot')}</Link>
        </div>
        <Button type="submit" variant="primary" size="lg" fullWidth loading={pending}>
          {t('login.submit')}
        </Button>
      </form>

      {sso ? (
        <p className={styles.hint}>
          <a href="/api/v1/auth/oidc/start">{t('login.sso')}</a>
        </p>
      ) : null}

      <p className={styles.hint}>{t('login.mfaHint')}</p>

      <div className={styles.meta}>
        <span>
          {t('login.noAccount')} <Link href="/register">{t('login.register')}</Link>
        </span>
      </div>
    </section>
  );
}

export default function LoginPage() {
  // useSearchParams needs a Suspense boundary for static prerendering.
  return (
    <Suspense fallback={null}>
      <LoginForm />
    </Suspense>
  );
}
