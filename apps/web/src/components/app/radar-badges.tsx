'use client';

import { Badge } from '@/components/ui';
import type { StatusTone } from '@/lib/format/status';
import { useTranslations } from '@/i18n/provider';
import type { RadarSeverity, RadarStatus } from '@/lib/api/types';

const SEVERITY_TONE: Record<RadarSeverity, StatusTone> = {
  INFO: 'info',
  LOW: 'neutral',
  MEDIUM: 'warning',
  HIGH: 'danger',
  CRITICAL: 'danger',
};

const STATUS_TONE: Record<RadarStatus, StatusTone> = {
  NEW: 'info',
  UNDER_REVIEW: 'accent',
  CONFIRMED: 'danger',
  DISMISSED: 'neutral',
  SNOOZED: 'neutral',
  RESOLVED: 'success',
  REOPENED: 'warning',
};

/** The severity as a word and a colour; the word is always there, colour alone never carries the meaning. */
export function SeverityBadge({ severity }: { readonly severity: RadarSeverity }) {
  const t = useTranslations('radar');
  return (
    <Badge tone={SEVERITY_TONE[severity]} variant={severity === 'CRITICAL' ? 'solid' : 'subtle'}>
      {t(`severity.${severity}` as 'severity.INFO')}
    </Badge>
  );
}

export function RadarStatusBadge({ status }: { readonly status: RadarStatus }) {
  const t = useTranslations('radar');
  return <Badge tone={STATUS_TONE[status]}>{t(`status.${status}` as 'status.NEW')}</Badge>;
}
