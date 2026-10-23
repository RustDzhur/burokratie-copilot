'use client';

import type { ReactNode } from 'react';
import { Card, CardBody } from '@/components/ui';
import { useTranslations } from '@/i18n/provider';
import type { PlanSummary } from '@/lib/api/types';
import styles from './plan-grid.module.css';

const euros = (cents: number): string => (cents / 100).toFixed(2).replace('.', ',');

interface PlanGridProps {
  readonly plans: readonly PlanSummary[];
  readonly currentCode?: string;
  /** What goes under each plan (a button, a link); receives the plan. */
  readonly action?: (plan: PlanSummary) => ReactNode;
}

/** The price list as cards: tier, price, who it is for, what is in it. The same cards on the landing page and in billing. */
export function PlanGrid({ plans, currentCode, action }: PlanGridProps) {
  const t = useTranslations('plans');
  const shown = [...plans].sort((a, b) => (a.tier ?? 0) - (b.tier ?? 0));
  return (
    <ul className={styles.grid}>
      {shown.map((plan) => {
        const known = ['FREE', 'PERSONAL', 'BUSINESS', 'PROFESSIONAL'].includes(plan.code);
        return (
          <li key={plan.code}>
            <Card>
              <CardBody>
                <div className={styles.card} data-current={plan.code === currentCode ? 'true' : undefined}>
                  <h3 className={styles.name}>{plan.name}</h3>
                  {known ? <p className={styles.tagline}>{t(`tagline.${plan.code}` as 'tagline.FREE')}</p> : null}
                  <p className={styles.price}>
                    {plan.priceCents === 0 ? t('price.free') : t('price.month', { price: euros(plan.priceCents) })}
                  </p>
                  {known ? <p className={styles.description}>{t(`description.${plan.code}` as 'description.FREE')}</p> : null}
                  <ul className={styles.features}>
                    {plan.analysesPerMonth !== null && plan.analysesPerMonth !== undefined ? (
                      <li>{t('feature.ANALYSES_LIMITED', { count: plan.analysesPerMonth })}</li>
                    ) : null}
                    {(plan.features ?? [])
                      .filter((feature) => feature !== 'ANALYSES_LIMITED')
                      .map((feature) => (
                        <li key={feature}>{t(`feature.${feature}` as 'feature.DOCUMENTS')}</li>
                      ))}
                  </ul>
                  {action ? <div className={styles.action}>{action(plan)}</div> : null}
                </div>
              </CardBody>
            </Card>
          </li>
        );
      })}
    </ul>
  );
}
