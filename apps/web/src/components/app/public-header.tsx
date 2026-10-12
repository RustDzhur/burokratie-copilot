'use client';

import Link from 'next/link';
import { buttonClass } from '@/components/ui/button-class';
import { useTranslations } from '@/i18n/provider';
import { LanguageMenu, ThemeMenu } from './preference-controls';
import styles from './public-header.module.css';

/** Minimal header for public pages (landing, auth): brand, preferences, CTAs. */
export function PublicHeader() {
  const t = useTranslations('nav');
  const tCommon = useTranslations('common');
  const tLanding = useTranslations('landing');

  return (
    <header className={styles.header}>
      <Link href="/" className={styles.brand}>
        <span className={styles.brandMark} aria-hidden="true">
          BC
        </span>
        <span className={styles.brandName}>{tCommon('app.name')}</span>
      </Link>
      <div className={styles.actions}>
        <ThemeMenu />
        <LanguageMenu />
        <Link href="/login" className={styles.loginLink}>
          {t('signIn')}
        </Link>
        <Link href="/register" className={buttonClass({ variant: 'primary', size: 'sm' })}>
          {tLanding('hero.primaryCta')}
        </Link>
      </div>
    </header>
  );
}
