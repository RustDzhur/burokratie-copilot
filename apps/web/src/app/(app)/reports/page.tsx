'use client';

import { useState } from 'react';
import { ExportButton } from '@/components/app/export-button';
import { PageHeader, PageSection, ResourceView } from '@/components/app/resource-view';
import { Card, CardBody, CardHeader, EmptyState, Select } from '@/components/ui';
import { Button } from '@/components/ui/button';
import { InlineAlert, SkeletonCards } from '@/components/ui/feedback';
import { formatDateTime, formatNumber } from '@/i18n/format';
import { useLocale, useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { useApiResource } from '@/lib/api/hooks';
import styles from './reports.module.css';

const REPORT_TYPES = ['documentVolume', 'processingTime', 'deadlines', 'costs'] as const;
const PERIODS = ['currentMonth', 'lastMonth', 'currentQuarter', 'currentYear'] as const;

function periodBounds(period: string): string {
  const now = new Date();
  const year = now.getFullYear();
  const month = now.getMonth();
  switch (period) {
    case 'lastMonth':
      return `${year}-${String(month === 0 ? 12 : month).padStart(2, '0')}`;
    case 'currentQuarter':
      return `${year}-Q${Math.floor(month / 3) + 1}`;
    case 'currentYear':
      return String(year);
    default:
      return `${year}-${String(month + 1).padStart(2, '0')}`;
  }
}

/**
 * Reports and analytics (§5.2.25).
 *
 * Every figure is aggregated by the backend (`noAggregation.notice`); the chart
 * only renders the series it receives, so the client can never disagree with the
 * server about a number.
 */
export default function ReportsPage() {
  const t = useTranslations('reports');
  const tNav = useTranslations('nav');
  const tCommon = useTranslations('common');
  const locale = useLocale();

  const [type, setType] = useState<string>('documentVolume');
  const [period, setPeriod] = useState<string>('currentMonth');

  const { state, reload, isRefreshing } = useApiResource(
    (signal) => api.reports.get(type, periodBounds(period), signal),
    [type, period],
    { keepPreviousData: true },
  );

  const maxValue =
    state.status === 'ready' && state.data.series.length > 0
      ? Math.max(...state.data.series.map((point) => point.value))
      : 0;

  return (
    <>
      <PageHeader
        title={tNav('items.reports')}
        subtitle={t('subtitle')}
        actions={
          <ExportButton
            label={t('action.export')}
            filename={`bericht-${type}-${periodBounds(period)}.csv`}
            onExport={() => api.reports.export(type, periodBounds(period))}
          />
        }
      />

      <div className={styles.controls}>
        <Select
          label={t('filter.type')}
          value={type}
          onChange={(event) => setType(event.target.value)}
          options={REPORT_TYPES.map((value) => ({
            value,
            label: t(`type.${value}` as 'type.documentVolume'),
          }))}
        />
        <Select
          label={t('filter.period')}
          value={period}
          onChange={(event) => setPeriod(event.target.value)}
          options={PERIODS.map((value) => ({
            value,
            label: periodBounds(value),
          }))}
        />
        <Button variant="secondary" iconLeft="refresh" onClick={reload} loading={isRefreshing}>
          {tCommon('actions.refresh')}
        </Button>
      </div>

      <ResourceView
        state={state}
        onRetry={reload}
        isRefreshing={isRefreshing}
        skeleton={<SkeletonCards count={4} />}
        isEmpty={(data) => data.series.length === 0 && Object.keys(data.kpis).length === 0}
        emptyState={
          <EmptyState
            icon="bar-chart"
            title={t('empty.title')}
            description={t('empty.description')}
            actions={
              <Button variant="secondary" onClick={() => setPeriod('currentYear')}>
                {t('empty.action')}
              </Button>
            }
          />
        }
      >
        {(data) => (
          <>
            <ul className={styles.kpiGrid}>
              {Object.entries(data.kpis).map(([key, value]) => (
                <li key={key}>
                  <Card>
                    <CardBody>
                      <p className={styles.kpiLabel}>{key}</p>
                      <p className={styles.kpiValue}>{formatNumber(value, locale)}</p>
                    </CardBody>
                  </Card>
                </li>
              ))}
            </ul>

            <PageSection title={t('chart.title')} description={t('chart.description')}>
              <Card>
                <CardHeader
                  title={t('chart.title')}
                  description={`${formatDateTime(data.generatedAt, locale)}`}
                  icon="bar-chart"
                  headingLevel={3}
                />
                <CardBody>
                  <ul className={styles.chart}>
                    {data.series.map((point) => (
                      <li key={point.period} className={styles.chartRow}>
                        <span className={styles.chartLabel}>{point.period}</span>
                        <span
                          className={styles.chartBar}
                          style={{
                            width: `${maxValue > 0 ? Math.round((point.value / maxValue) * 100) : 0}%`,
                          }}
                          aria-hidden="true"
                        />
                        <span className={styles.chartValue}>
                          {formatNumber(point.value, locale)}
                        </span>
                      </li>
                    ))}
                  </ul>
                </CardBody>
              </Card>
            </PageSection>
          </>
        )}
      </ResourceView>

      <InlineAlert tone="info">{t('noAggregation.notice')}</InlineAlert>
    </>
  );
}
