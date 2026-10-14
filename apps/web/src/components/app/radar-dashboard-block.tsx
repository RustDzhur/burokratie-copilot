'use client';

import Link from 'next/link';
import { SeverityBadge } from '@/components/app/radar-badges';
import { PageSection } from '@/components/app/resource-view';
import { Card, CardBody } from '@/components/ui';
import { useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { useApiResource } from '@/lib/api/hooks';
import { useSession } from '@/lib/auth/session-context';

/**
 * The Radar on the dashboard: how many findings wait, how many are confirmed, and the newest open ones, each opening its
 * evidence. Hidden for a plan without the Radar (the server would refuse the call), and silent when there is nothing.
 */
export function RadarDashboardBlock() {
  const t = useTranslations('radar');
  const session = useSession();
  const included = (session?.subscription?.planTier ?? 0) >= 1;
  const { state } = useApiResource(
    async (signal) => {
      const [summary, open] = await Promise.all([
        api.radar.summary(signal),
        api.radar.list({ open: 'true', size: 5 }, signal),
      ]);
      return { summary, open };
    },
    [included],
    { skip: !included },
  );

  if (!included || state.status === 'loading' || state.status === 'error') return null;
  const { summary, open } = state.data;
  const waiting = (summary.byStatus.NEW ?? 0) + (summary.byStatus.REOPENED ?? 0) + (summary.byStatus.UNDER_REVIEW ?? 0);
  const confirmed = summary.byStatus.CONFIRMED ?? 0;
  if (open.items.length === 0 && summary.recentlyResolved.length === 0) return null;

  return (
    <PageSection title={t('dashboard.title')}>
      <Card>
        <CardBody>
          <p data-testid="radar-counts">{t('dashboard.counts', { waiting, confirmed })}</p>
          {open.items.length > 0 ? (
            <ul style={{ listStyle: 'none', padding: 0, display: 'grid', gap: '0.5rem' }}>
              {open.items.map((finding) => (
                <li key={finding.id}>
                  <SeverityBadge severity={finding.severity} /> <Link href={`/radar/${finding.id}`}>{finding.title}</Link>
                </li>
              ))}
            </ul>
          ) : null}
          {summary.recentlyResolved.length > 0 ? (
            <>
              <h3>{t('dashboard.recentlyResolved')}</h3>
              <ul style={{ listStyle: 'none', padding: 0 }}>
                {summary.recentlyResolved.map((finding) => (
                  <li key={finding.id}>
                    <Link href={`/radar/${finding.id}`}>{finding.title}</Link>
                  </li>
                ))}
              </ul>
            </>
          ) : null}
          <Link href="/radar">{t('dashboard.all')}</Link>
        </CardBody>
      </Card>
    </PageSection>
  );
}
