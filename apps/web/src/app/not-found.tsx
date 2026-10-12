import Link from 'next/link';
import { buttonClass } from '@/components/ui/button-class';
import { getServerTranslations } from '@/i18n/server';
import styles from './status-page.module.css';

/** 404 page — never a blank screen (docs/task.md §29). */
export default async function NotFound() {
  const { t } = await getServerTranslations();
  return (
    <main className={styles.page} id="main-content">
      <h1 className={styles.title}>{t('states', 'notFound.title')}</h1>
      <p className={styles.text}>{t('states', 'notFound.description')}</p>
      <Link href="/dashboard" className={buttonClass({ variant: 'primary' })}>
        {t('nav', 'items.dashboard')}
      </Link>
    </main>
  );
}
