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

// The audiences the backend reports for (ReportService.Audience). The Kanzlei starts on the office report; the
// household report stays reachable because the report service still computes it (§5.9 reworks reports separately).
const REPORT_TYPES = ['company', 'individual'] as const;
const PERIODS = ['currentMonth', 'lastMonth', 'currentQuarter', 'currentYear'] as const;

const isoDay = (date: Date): string =>
  `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;

/** `from..to` (inclusive ISO days) for one of the selectable periods; the reports API takes both ends. */
function periodBounds(period: string): string {
  const now = new Date();
  const year = now.getFullYear();
  const month = now.getMonth();
  switch (period) {
    case 'lastMonth':
      return `${isoDay(new Date(year, month - 1, 1))}..${isoDay(new Date(year, month, 0))}`;
    case 'currentQuarter': {
      const first = Math.floor(month / 3) * 3;
      return `${isoDay(new Date(year, first, 1))}..${isoDay(new Date(year, first + 3, 0))}`;
    }
    case 'currentYear':
      return `${isoDay(new Date(year, 0, 1))}..${isoDay(new Date(year, 11, 31))}`;
    default:
      return `${isoDay(new Date(year, month, 1))}..${isoDay(new Date(year, month + 1, 0))}`;
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

  const [type, setType] = useState<string>('company');
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
            label: t(`type.${value}` as 'type.individual'),
          }))}
        />
        <Select
          label={t('filter.period')}
          value={period}
          onChange={(event) => setPeriod(event.target.value)}
          options={PERIODS.map((value) => ({
            value,
            label: periodBounds(value).replace('..', ' – '),
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
