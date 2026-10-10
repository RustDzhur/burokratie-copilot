import Link from 'next/link';
import type { ReactNode } from 'react';
import { ThemeMenu, LanguageMenu } from '@/components/app/preference-controls';
import { getServerTranslations } from '@/i18n/server';
import styles from './auth-layout.module.css';

/** Centered form layout for sign-in, registration, verification and reset. */
export default async function AuthLayout({ children }: { children: ReactNode }) {
  const { t } = await getServerTranslations();

  return (
    <div className={styles.shell}>
      <a className={styles.skipLink} href="#main-content">
        {t('nav', 'skipToContent')}
      </a>
      <header className={styles.header}>
        <Link href="/" className={styles.brand}>
          <span className={styles.brandMark} aria-hidden="true">
            BC
          </span>
          <span>{t('common', 'app.name')}</span>
        </Link>
        <div className={styles.controls}>
          <ThemeMenu />
          <LanguageMenu />
        </div>
      </header>
      <main id="main-content" className={styles.main}>
        {children}
      </main>
      <p className={styles.note}>{t('auth', 'security.note')}</p>
    </div>
  );
}
