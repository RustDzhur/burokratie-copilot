import { CookieSettingsButton } from '@/components/app/cookie-consent';
import Link from 'next/link';
import { BrandMark } from '@/components/app/brand-mark';
import type { ReactNode } from 'react';
import { ThemeMenu, LanguageMenu } from '@/components/app/preference-controls';
import { getServerTranslations } from '@/i18n/server';
import { LEGAL_LINKS } from '@/legal/links';
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
          <BrandMark size={40} />
          <span>{t('common', 'app.name')}</span>
        </Link>
        <div className={styles.controls}>
          <ThemeMenu />
          <LanguageMenu />
        </div>
      </header>
      <p className={styles.pilot}>{t('common', 'pilot.banner')}</p>
      <main id="main-content" className={styles.main}>
        {children}
      </main>
      <p className={styles.note} lang="de">
        {t('common', 'app.slogan')}
      </p>
      <p className={styles.note}>{t('auth', 'security.note')}</p>
      <nav className={styles.note} aria-label="Rechtliche Hinweise" lang="de" style={{ display: 'flex', gap: '1rem', flexWrap: 'wrap', justifyContent: 'center' }}>
        {LEGAL_LINKS.map((link) => (
          <Link key={link.href} href={link.href}>
            {link.label}
          </Link>
        ))}
      <CookieSettingsButton />
      </nav>
    </div>
  );
}
