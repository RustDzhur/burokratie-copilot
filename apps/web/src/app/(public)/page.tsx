'use client';

import Link from 'next/link';
import { BrandMark } from '@/components/app/brand-mark';
import { Icon, type IconName } from '@/components/icons';
import { buttonClass } from '@/components/ui/button-class';
import { Card } from '@/components/ui/data';
import { useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { useApiResource } from '@/lib/api/hooks';
import { PlanGrid } from '@/components/app/plan-grid';
import { DemoDialog } from '@/components/app/demo-dialog';
import { useState } from 'react';
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
 * The numbers are the platform's real totals, counted from the database (documents captured, cases created, tasks completed).
 * Nothing on this page is sample data; a demo cabinet's invented documents are not counted.
 */
export default function LandingPage() {
  const t = useTranslations('landing');
  const tCommon = useTranslations('common');
  const tDemo = useTranslations('demo');
  const [demoOpen, setDemoOpen] = useState(false);


  return (
    <div className={styles.page}>
      <DemoDialog open={demoOpen} onClose={() => setDemoOpen(false)} />
      <section className={styles.hero} aria-labelledby="landing-hero-title">
        <div className={styles.brandHero}>
          <BrandMark size={120} />
          <p className={styles.slogan} lang="de">
            {tCommon('app.slogan')}
          </p>
        </div>
        <p className={styles.eyebrow}>{t('hero.eyebrow')}</p>
        <h1 className={styles.heroTitle} id="landing-hero-title">
          {t('hero.title')}
        </h1>
        <p className={styles.heroSubtitle}>{t('hero.subtitle')}</p>
        <div className={styles.heroActions}>
          <Link href="/register" className={buttonClass({ variant: 'neon', size: 'lg' })}>
            {t('hero.primaryCta')}
          </Link>
          <button type="button" className={buttonClass({ variant: 'secondary', size: 'lg' })} onClick={() => setDemoOpen(true)}>
            {tDemo('cta')}
          </button>
          <a href="#features" className={buttonClass({ variant: 'ghost', size: 'lg' })}>
            {t('hero.secondaryCta')}
          </a>
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
      <PlanGrid plans={plans} />
      <p className={styles.featureText}>{t('pricing.afterTrial', { days: retentionDays })}</p>
    </section>
  );
}
