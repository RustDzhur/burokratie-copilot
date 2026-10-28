'use client';

import { ListPage } from '@/components/app/list-page';
import { RadarStatusBadge, SeverityBadge } from '@/components/app/radar-badges';
import type { Column } from '@/components/ui';
import { formatDate } from '@/i18n/format';
import { useLocale, useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import type { RadarFinding } from '@/lib/api/types';

const STATUSES = ['NEW', 'UNDER_REVIEW', 'CONFIRMED', 'SNOOZED', 'REOPENED', 'RESOLVED', 'DISMISSED'] as const;
const SEVERITIES = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'INFO'] as const;
const TYPES = [
  'CONTRACT_RISK',
  'INVOICE_ANOMALY',
  'MISSING_INFORMATION',
  'UNRESOLVED_CASE',
  'CONFLICTING_FACTS',
  'OPERATIONAL_RISK',
] as const;

/** The Radar Center: what the platform noticed, worst first; each row opens the evidence and the decisions. */
export default function RadarPage() {
  const t = useTranslations('radar');
  const locale = useLocale();

  const columns: readonly Column<RadarFinding>[] = [
    { key: 'severity', header: t('column.severity'), render: (row) => <SeverityBadge severity={row.severity} /> },
    { key: 'title', header: t('column.title'), link: (row) => `/radar/${row.id}`, render: (row) => row.title },
    { key: 'type', header: t('column.type'), render: (row) => t(`type.${row.type}` as 'type.CONTRACT_RISK') },
    { key: 'status', header: t('column.status'), render: (row) => <RadarStatusBadge status={row.status} /> },
    { key: 'detected', header: t('column.detected'), render: (row) => formatDate(row.detectedAt, locale) },
    { key: 'evidence', header: t('column.evidence'), render: (row) => String(row.evidenceCount) },
  ];

  return (
    <ListPage<RadarFinding>
      screenId="radar"
      namespace="radar"
      searchable={false}
      emptyIcon="alert-triangle"
      load={(params, signal) => api.radar.list(params, signal)}
      columns={columns}
      filters={[
        { key: 'status', label: t('filter.status'), options: STATUSES.map((value) => ({ value, label: t(`status.${value}` as 'status.NEW') })) },
        { key: 'severity', label: t('filter.severity'), options: SEVERITIES.map((value) => ({ value, label: t(`severity.${value}` as 'severity.INFO') })) },
        { key: 'type', label: t('filter.type'), options: TYPES.map((value) => ({ value, label: t(`type.${value}` as 'type.CONTRACT_RISK') })) },
      ]}
    />
  );
}
