import Link from 'next/link';
import type { ReactNode } from 'react';
import { PublicHeader } from '@/components/app/public-header';
import { getServerTranslations } from '@/i18n/server';
import styles from './public-layout.module.css';

/**
 * Public layout for the landing page: minimal header, footer with the legally
 * relevant links and no application chrome. Strings come from the same
 * catalogue as the rest of the product (server-side translation).
 */
export default async function PublicLayout({ children }: { children: ReactNode }) {
  const { t } = await getServerTranslations();

  return (
    <div className={styles.shell}>
      <a className={styles.skipLink} href="#main-content">
        {t('nav', 'skipToContent')}
      </a>
      <PublicHeader />
      <main id="main-content" className={styles.main}>
        {children}
      </main>
      <footer className={styles.footer}>
        <p>
          © {new Date().getFullYear()} {t('common', 'app.name')} · {t('landing', 'footer.rights')}
        </p>
        <nav className={styles.footerNav} aria-label={t('landing', 'footer.privacy')}>
          <Link href="/accessibility">{t('nav', 'items.accessibilitySettings')}</Link>
          <Link href="/login">{t('nav', 'items.login')}</Link>
          <Link href="/register">{t('nav', 'items.register')}</Link>
        </nav>
      </footer>
    </div>
  );
}
