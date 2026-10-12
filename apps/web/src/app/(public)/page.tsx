'use client';

import Link from 'next/link';
import { Icon, type IconName } from '@/components/icons';
import { buttonClass } from '@/components/ui/button-class';
import { Badge, Card } from '@/components/ui/data';
import { useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { useApiResource } from '@/lib/api/hooks';
import styles from './landing.module.css';

interface FeatureItem {
  readonly titleKey:
    | 'features.capture.title'
    | 'features.understand.title'
    | 'features.act.title'
    | 'features.prove.title';
  readonly descriptionKey:
    | 'features.capture.description'
    | 'features.understand.description'
    | 'features.act.description'
    | 'features.prove.description';
  readonly icon: IconName;
}

const FEATURES: readonly FeatureItem[] = [
  {
    titleKey: 'features.capture.title',
    descriptionKey: 'features.capture.description',
    icon: 'inbox',
  },
  {
    titleKey: 'features.understand.title',
    descriptionKey: 'features.understand.description',
    icon: 'sparkles',
  },
  {
    titleKey: 'features.act.title',
    descriptionKey: 'features.act.description',
    icon: 'check-circle',
  },
  {
    titleKey: 'features.prove.title',
    descriptionKey: 'features.prove.description',
    icon: 'shield',
  },
];

const TRUST_KEYS = ['trust.gdpr', 'trust.audit', 'trust.access', 'trust.hosting'] as const;

/**
 * Public landing page.
 *
 * This is the only screen allowed to show illustrative numbers; they are marked
 * as sample data (`landing.hero.demoNotice` + a visible badge) so they can never
 * be mistaken for real records of a customer workspace.
 */
export default function LandingPage() {
  const t = useTranslations('landing');
  const tCommon = useTranslations('common');

  const demoStats = [
    { labelKey: 'stats.documents', value: '128.400' },
    { labelKey: 'stats.cases', value: '9.750' },
    { labelKey: 'stats.hours', value: '6,5' },
  ] as const;

  return (
    <div className={styles.page}>
      <section className={styles.hero} aria-labelledby="landing-hero-title">
        <p className={styles.eyebrow}>{t('hero.eyebrow')}</p>
        <h1 className={styles.heroTitle} id="landing-hero-title">
          {t('hero.title')}
        </h1>
        <p className={styles.heroSubtitle}>{t('hero.subtitle')}</p>
        <div className={styles.heroActions}>
          <Link href="/register" className={buttonClass({ variant: 'neon', size: 'lg' })}>
            {t('hero.primaryCta')}
          </Link>
          <a href="#features" className={buttonClass({ variant: 'secondary', size: 'lg' })}>
            {t('hero.secondaryCta')}
          </a>
        </div>

        <div className={styles.demoStats}>
          <p className={styles.demoNotice}>
            <Icon name="info" size={15} />
            <span>{t('hero.demoNotice')}</span>
          </p>
          <dl className={styles.statGrid}>
            {demoStats.map((stat) => (
              <div key={stat.labelKey} className={styles.stat}>
                <dt>{t(stat.labelKey)}</dt>
                <dd>
                  {stat.value}
                  <Badge tone="warning" variant="outline" icon="info">
                    {tCommon('labels.demoData')}
                  </Badge>
                </dd>
              </div>
            ))}
          </dl>
        </div>
      </section>

      <section className={styles.section} id="features" aria-labelledby="landing-features-title">
        <header className={styles.sectionHeader}>
          <h2 className={styles.sectionTitle} id="landing-features-title">
            {t('features.title')}
          </h2>
          <p className={styles.sectionSubtitle}>{t('features.subtitle')}</p>
        </header>
        <ul className={styles.featureGrid}>
          {FEATURES.map((feature) => (
            <li key={feature.titleKey}>
              <Card tone="glass" interactive>
                <span className={styles.featureIcon} aria-hidden="true">
                  <Icon name={feature.icon} size={20} />
                </span>
                <h3 className={styles.featureTitle}>{t(feature.titleKey)}</h3>
                <p className={styles.featureText}>{t(feature.descriptionKey)}</p>
              </Card>
            </li>
          ))}
        </ul>
      </section>

      <PricingSection />

      <section className={styles.section} aria-labelledby="landing-trust-title">
        <div className={styles.twoColumn}>
          <div>
            <h2 className={styles.sectionTitle} id="landing-trust-title">
              {t('trust.title')}
            </h2>
            <ul className={styles.checkList}>
              {TRUST_KEYS.map((key) => (
                <li key={key}>
                  <Icon name="check-circle" size={17} />
                  <span>{t(key)}</span>
                </li>
              ))}
            </ul>
          </div>
          <Card tone="glass">
            <h3 className={styles.featureTitle}>{t('a11y.title')}</h3>
            <p className={styles.featureText}>{t('a11y.description')}</p>
            <Link href="/accessibility" className={styles.inlineLink}>
              {t('a11y.link')}
              <Icon name="arrow-right" size={16} />
            </Link>
          </Card>
        </div>
      </section>

      <section className={styles.cta} aria-labelledby="landing-cta-title">
        <h2 className={styles.ctaTitle} id="landing-cta-title">
          {t('cta.title')}
        </h2>
        <p className={styles.ctaText}>{t('cta.description')}</p>
        <Link href="/register" className={buttonClass({ variant: 'neon', size: 'lg' })}>
          {t('hero.primaryCta')}
        </Link>
      </section>
    </div>
  );
}

const PLAN_HINTS = {
  PRIVATE: 'pricing.hint.PRIVATE',
  FAMILY: 'pricing.hint.FAMILY',
  COMPANY: 'pricing.hint.COMPANY',
} as const;

/** The price list and the terms of the free trial, read from the server so the page never disagrees with it. */
function PricingSection() {
  const t = useTranslations('landing');
  const { state } = useApiResource((signal) => api.publicPlans.get(signal), []);
  if (state.status !== 'ready' || state.data.plans.length === 0) return null;
  const { plans, trialDays, retentionDays } = state.data;
  return (
    <section className={styles.section} id="pricing" aria-labelledby="landing-pricing-title">
      <h2 className={styles.sectionTitle} id="landing-pricing-title">
        {t('pricing.title')}
      </h2>
      <p className={styles.featureText}>{t('pricing.trial', { days: trialDays })}</p>
      <ul className={styles.featureGrid}>
        {plans.map((plan) => (
          <li key={plan.code}>
            <Card tone="glass">
              <h3 className={styles.featureTitle}>{plan.name}</h3>
              <p className={styles.featureTitle}>
                {t('pricing.perMonth', { price: (plan.priceCents / 100).toFixed(2).replace('.', ',') })}
              </p>
              <p className={styles.featureText}>
                {PLAN_HINTS[plan.code as keyof typeof PLAN_HINTS] ? t(PLAN_HINTS[plan.code as keyof typeof PLAN_HINTS]) : ''}
              </p>
            </Card>
          </li>
        ))}
      </ul>
      <p className={styles.featureText}>{t('pricing.afterTrial', { days: retentionDays })}</p>
    </section>
  );
}
