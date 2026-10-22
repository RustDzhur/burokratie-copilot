'use client';

import Link from 'next/link';
import { Icon, type IconName } from '@/components/icons';
import { Card, CardBody, CardHeader } from '@/components/ui/data';
import { EmptyState, InlineAlert } from '@/components/ui/feedback';
import {
  CardsSkeleton,
  PageHeader,
  PageSection,
  ResourceView,
} from '@/components/app/resource-view';
import { useFormatters, useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { useApiResource } from '@/lib/api/hooks';
import type { DashboardSummary } from '@/lib/api/types';
import { DashboardBusinessBlock } from '@/components/app/dashboard-business-block';
import styles from './dashboard.module.css';

interface AttentionCard {
  readonly countKey: string;
  readonly titleKey:
    | 'card.pendingDocuments.title'
    | 'card.todayTasks.title'
    | 'card.upcomingDeadlines.title'
    | 'card.criticalNotifications.title'
    | 'card.pendingApprovals.title'
    | 'card.contractRenewals.title'
    | 'card.invoicesToReview.title'
    | 'card.overdueTasks.title'
    | 'card.casesMissingDocuments.title';
  readonly descriptionKey:
    | 'card.pendingDocuments.description'
    | 'card.todayTasks.description'
    | 'card.upcomingDeadlines.description'
    | 'card.criticalNotifications.description'
    | 'card.pendingApprovals.description'
    | 'card.contractRenewals.description'
    | 'card.invoicesToReview.description'
    | 'card.overdueTasks.description'
    | 'card.casesMissingDocuments.description';
  readonly href: string;
  readonly icon: IconName;
}

const ATTENTION_CARDS: readonly AttentionCard[] = [
  {
    countKey: 'pendingDocuments',
    titleKey: 'card.pendingDocuments.title',
    descriptionKey: 'card.pendingDocuments.description',
    href: '/inbox',
    icon: 'inbox',
  },
  {
    countKey: 'todayTasks',
    titleKey: 'card.todayTasks.title',
    descriptionKey: 'card.todayTasks.description',
    href: '/tasks',
    icon: 'check-circle',
  },
  {
    countKey: 'overdueTasks',
    titleKey: 'card.overdueTasks.title',
    descriptionKey: 'card.overdueTasks.description',
    href: '/tasks',
    icon: 'alert-triangle',
  },
  {
    countKey: 'upcomingDeadlines',
    titleKey: 'card.upcomingDeadlines.title',
    descriptionKey: 'card.upcomingDeadlines.description',
    href: '/calendar',
    icon: 'calendar',
  },
  {
    countKey: 'criticalNotifications',
    titleKey: 'card.criticalNotifications.title',
    descriptionKey: 'card.criticalNotifications.description',
    href: '/notifications',
    icon: 'bell',
  },
  {
    countKey: 'pendingApprovals',
    titleKey: 'card.pendingApprovals.title',
    descriptionKey: 'card.pendingApprovals.description',
    href: '/company',
    icon: 'check-square',
  },
  {
    countKey: 'contractRenewals',
    titleKey: 'card.contractRenewals.title',
    descriptionKey: 'card.contractRenewals.description',
    href: '/contracts',
    icon: 'layout',
  },
  {
    countKey: 'invoicesToReview',
    titleKey: 'card.invoicesToReview.title',
    descriptionKey: 'card.invoicesToReview.description',
    href: '/invoices',
    icon: 'receipt',
  },
  {
    countKey: 'casesMissingDocuments',
    titleKey: 'card.casesMissingDocuments.title',
    descriptionKey: 'card.casesMissingDocuments.description',
    href: '/cases',
    icon: 'folder',
  },
];

/**
 * Dashboard (docs/task.md §5.3): every block is an action, not decoration.
 * Counts and activity come from a single aggregated backend call; financial and
 * personal figures are only rendered when the backend includes them for the
 * current role — the client never invents or re-derives permissions.
 */
export default function DashboardPage() {
  const t = useTranslations('dashboard');
  const tNav = useTranslations('nav');
  const formatters = useFormatters();
  const { state, reload, isRefreshing } = useApiResource<DashboardSummary>(
    (signal) => api.dashboard.summary(signal),
    [],
  );

  return (
    <>
      <PageHeader
        title={tNav('items.dashboard')}
        subtitle={t('subtitle')}
        meta={
          state.status === 'ready'
            ? t('refreshHint', { when: formatters.relativeTime(state.data.generatedAt) })
            : undefined
        }
      />

      <ResourceView
        state={state}
        onRetry={reload}
        isRefreshing={isRefreshing}
        skeleton={<CardsSkeleton count={6} />}
        isEmpty={(data) => Object.keys(data.counts).length === 0 && data.activity.length === 0}
        emptyState={
          <EmptyState
            icon="check-circle"
            title={t('empty.noItems')}
            description={t('empty.noItemsHint')}
          />
        }
      >
        {(data) => (
          <>
            <DashboardBusinessBlock business={data.business} />
            <PageSection title={t('sections.attention')}>
              <ul className={styles.cardGrid} data-tour="attention">
                {ATTENTION_CARDS.map((card) => {
                  const count = data.counts[card.countKey] ?? 0;
                  return (
                    <li key={card.countKey}>
                      <Card tone={count > 0 ? 'accent' : 'default'} interactive>
                        <CardHeader
                          title={
                            <Link href={card.href} className={styles.cardLink}>
                              {t(card.titleKey)}
                            </Link>
                          }
                          description={t(card.descriptionKey)}
                          icon={card.icon}
                          badge={
                            <span className={styles.count} aria-hidden="true">
                              {count}
                            </span>
                          }
                          headingLevel={3}
                        />
                        <CardBody>
                          <p className={styles.cardMetric}>
                            <span className={styles.visuallyHidden}>
                              {t('metric.open', { count })}
                            </span>
                            <span aria-hidden="true">{count}</span>
                          </p>
                          <Link href={card.href} className={styles.cardAction}>
                            {t('viewAll')}
                            <Icon name="arrow-right" size={15} />
                          </Link>
                        </CardBody>
                      </Card>
                    </li>
                  );
                })}
              </ul>
            </PageSection>

            <div className={styles.twoColumn}>
              <PageSection title={t('sections.activity')}>
                {data.activity.length === 0 ? (
                  <EmptyState
                    icon="clock"
                    title={t('empty.noItems')}
                    description={t('empty.noItemsHint')}
                  />
                ) : (
                  <ul className={styles.timeline}>
                    {data.activity.map((entry) => (
                      <li key={entry.id} className={styles.timelineItem}>
                        <span className={styles.timelineDot} aria-hidden="true" />
                        <div>
                          <p className={styles.timelineSummary}>{entry.summary}</p>
                          <p className={styles.timelineMeta}>
                            {entry.actor} · {formatters.relativeTime(entry.at)}
                            {entry.target ? ` · ${entry.target}` : ''}
                          </p>
                        </div>
                      </li>
                    ))}
                  </ul>
                )}
              </PageSection>

              <PageSection title={t('sections.system')}>
                <div className={styles.systemCards}>
                  <Card>
                    <CardHeader
                      title={t('card.automations.title')}
                      description={t('card.automations.description')}
                      icon="zap"
                      headingLevel={3}
                    />
                    <CardBody>
                      <ul className={styles.statList}>
                        {data.automationStatus.map((entry) => (
                          <li key={entry.status}>
                            <span>{entry.status}</span>
                            <strong>{entry.count}</strong>
                          </li>
                        ))}
                      </ul>
                      <Link href="/automations" className={styles.cardAction}>
                        {t('viewAll')}
                        <Icon name="arrow-right" size={15} />
                      </Link>
                    </CardBody>
                  </Card>
                  <Card>
                    <CardHeader
                      title={t('card.integrations.title')}
                      description={t('card.integrations.description')}
                      icon="plug"
                      headingLevel={3}
                    />
                    <CardBody>
                      <ul className={styles.statList}>
                        {data.integrationStatus.map((entry) => (
                          <li key={entry.status}>
                            <span>{entry.status}</span>
                            <strong>{entry.count}</strong>
                          </li>
                        ))}
                      </ul>
                      <Link href="/integrations" className={styles.cardAction}>
                        {t('viewAll')}
                        <Icon name="arrow-right" size={15} />
                      </Link>
                    </CardBody>
                  </Card>
                </div>
                <InlineAlert tone="info">{t('restrictedNotice')}</InlineAlert>
              </PageSection>
            </div>
          </>
        )}
      </ResourceView>
    </>
  );
}
