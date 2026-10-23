'use client';

import { Badge } from '@/components/ui';
import { formatDate } from '@/i18n/format';
import { useLocale, useTranslations } from '@/i18n/provider';

export interface DeadlineBadgeProps {
  /** The deadline as an ISO date; null/absent means the voucher has no active deadline. */
  readonly date?: string | null;
  /** Days from today to the deadline; negative once it has passed. */
  readonly daysLeft?: number | null;
}

/** The tone of a deadline badge, kept apart from the rendering so a test can state the rule. */
export function deadlineTone(daysLeft: number): 'danger' | 'warning' | 'neutral' {
  if (daysLeft < 3) {
    return 'danger';
  }
  if (daysLeft <= 14) {
    return 'warning';
  }
  return 'neutral';
}

/**
 * The deadline badge of a voucher (Kanzlei edition, §5.1).
 *
 * <p>Red under three days and for a deadline that has passed, yellow up to two weeks, neutral beyond
 * that. The word inside the badge carries the same information as the colour, so the urgency is not
 * conveyed by colour alone; the exact date is in the tooltip for the person who has to plan around it.
 * No deadline means no badge — an empty cell is honest, a green "keine Frist" would not be.</p>
 */
export function DeadlineBadge({ date, daysLeft }: DeadlineBadgeProps) {
  const t = useTranslations('documents');
  const locale = useLocale();
  if (!date || daysLeft === null || daysLeft === undefined) {
    return null;
  }
  const overdue = daysLeft < 0;
  return (
    <span title={t('deadline.on', { date: formatDate(date, locale) })}>
      <Badge tone={overdue ? 'danger' : deadlineTone(daysLeft)} variant="outline">
        {overdue ? t('deadline.overdue') : t('deadline.daysLeft', { days: daysLeft })}
      </Badge>
    </span>
  );
}
