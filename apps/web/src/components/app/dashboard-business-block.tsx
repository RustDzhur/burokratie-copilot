'use client';

import Link from 'next/link';
import { PageSection } from '@/components/app/resource-view';
import { Card, CardBody } from '@/components/ui';
import { useFormatters, useTranslations } from '@/i18n/provider';
import type { DashboardSummary } from '@/lib/api/types';

type Business = NonNullable<DashboardSummary['business']>;

/**
 * Money owed to and by the business, and open tasks per person. Every part appears only if the server sent it, i.e.
 * only if the reader may see it; nothing is derived or converted on the client.
 */
export function DashboardBusinessBlock({ business }: { readonly business?: Business }) {
  const t = useTranslations('dashboard');
  const formatters = useFormatters();
  if (!business || (!business.receivables && !business.payables && !business.tasksByPerson)) return null;

  const money = (title: string, rows: NonNullable<Business['receivables']>) => (
    <div>
      <h3>{title}</h3>
      {rows.length === 0 ? (
        <p>{t('business.none')}</p>
      ) : (
        <ul style={{ listStyle: 'none', padding: 0 }}>
          {rows.map((row) => (
            <li key={row.currency}>
              <strong>{formatters.currency(row.open_amount, row.currency)}</strong>{' '}
              {t('business.line', {
                count: row.count,
                overdue: row.overdue_count,
                overdueAmount: formatters.currency(row.overdue_amount, row.currency),
              })}
            </li>
          ))}
        </ul>
      )}
      <Link href="/invoices">{t('viewAll')}</Link>
    </div>
  );

  return (
    <PageSection title={t('business.title')}>
      <Card>
        <CardBody>
          {business.receivables ? money(t('business.receivables'), business.receivables) : null}
          {business.payables ? money(t('business.payables'), business.payables) : null}
          {business.tasksByPerson && business.tasksByPerson.length > 0 ? (
            <div>
              <h3>{t('business.people')}</h3>
              <ul style={{ listStyle: 'none', padding: 0 }}>
                {business.tasksByPerson.map((person) => (
                  <li key={person.user_id}>
                    <strong>{person.name}</strong>: {t('business.person', { open: person.open, overdue: person.overdue })}
                  </li>
                ))}
              </ul>
              <Link href="/tasks">{t('viewAll')}</Link>
            </div>
          ) : null}
        </CardBody>
      </Card>
    </PageSection>
  );
}
