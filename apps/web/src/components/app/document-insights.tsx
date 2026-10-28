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
 * On the page of one document: the open Radar findings that cite it and the cases it belongs to. The Radar part is
 * left out for a plan without the Radar (the server would refuse it); a failed load shows nothing rather than a guess.
 */
export function DocumentInsights({ documentId }: { readonly documentId: string }) {
  const t = useTranslations('documentViewer');
  const session = useSession();
  const radarIncluded = (session?.subscription?.planTier ?? 0) >= 1;
  const { state } = useApiResource(
    async (signal) => {
      const [findings, cases] = await Promise.all([
        radarIncluded ? api.radar.list({ documentId, open: 'true', size: 10 }, signal) : Promise.resolve(null),
        api.documents.cases(documentId, signal),
      ]);
      return { findings, cases };
    },
    [documentId, radarIncluded],
  );
  if (state.status !== 'ready') return null;
  const { findings, cases } = state.data;

  return (
    <PageSection title={t('insights.title')}>
      <Card>
        <CardBody>
          {findings ? (
            <>
              <h3>{t('insights.risks')}</h3>
              {findings.items.length === 0 ? (
                <p>{t('insights.noRisks')}</p>
              ) : (
                <ul style={{ listStyle: 'none', padding: 0, display: 'grid', gap: '0.5rem' }}>
                  {findings.items.map((finding) => (
                    <li key={finding.id}>
                      <SeverityBadge severity={finding.severity} /> <Link href={`/radar/${finding.id}`}>{finding.title}</Link>
                    </li>
                  ))}
                </ul>
              )}
            </>
          ) : null}
          <h3>{t('insights.cases')}</h3>
          {cases.length === 0 ? (
            <p>{t('insights.noCases')}</p>
          ) : (
            <ul style={{ listStyle: 'none', padding: 0, display: 'grid', gap: '0.5rem' }}>
              {cases.map((item) => (
                <li key={item.id}>
                  <Link href={`/cases/${item.id}`}>{item.title}</Link>
                </li>
              ))}
            </ul>
          )}
        </CardBody>
      </Card>
    </PageSection>
  );
}
