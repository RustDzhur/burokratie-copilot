'use client';

import Link from 'next/link';
import { useTranslations } from '@/i18n/provider';
import type { SubscriptionInfo } from '@/lib/api/types';
import styles from './subscription-banner.module.css';

/**
 * A thin line above the page while the free trial runs (or the paid period is about to end): how many days are
 * left and where to choose a plan. Nothing is shown for a comfortably paid subscription.
 */
export function SubscriptionBanner({
  subscription,
  hidden,
}: {
  readonly subscription: SubscriptionInfo | null;
  readonly hidden: boolean;
}) {
  const t = useTranslations('billing');
  if (!subscription || hidden || subscription.blocked) return null;
  const trial = subscription.status === 'TRIAL';
  const endingSoon = subscription.status === 'ACTIVE' && subscription.daysLeft <= 7;
  if (!trial && !endingSoon) return null;
  return (
    <p className={`${styles.banner} ${subscription.daysLeft <= 7 ? styles.urgent : ''}`} role="status">
      <span>
        {trial
          ? t('banner.trial', { days: subscription.daysLeft })
          : t('banner.ending', { days: subscription.daysLeft })}
      </span>
      <Link href="/billing" className={styles.link}>
        {t('banner.action')}
      </Link>
    </p>
  );
}
