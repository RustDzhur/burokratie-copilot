'use client';

import { useState } from 'react';
import { ExportButton } from '@/components/app/export-button';
import { Card, CardBody, CardHeader, EmptyState, Input } from '@/components/ui';
import { InlineAlert, SkeletonCards } from '@/components/ui/feedback';
import { formatNumber } from '@/i18n/format';
import { useLocale, useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { useApiResource } from '@/lib/api/hooks';

/**
 * Expenses as the tax adviser sees them: the year's categorised receipts grouped by EÜR line.
 *
 * <p>The table is the same data the CSV export contains, so the screen and the file cannot tell
 * different stories. A document without a category, a line or a date is not hidden: it appears with
 * its warning, because hiding a receipt is exactly the failure this section exists to prevent.</p>
 */
export function ExpenseElsterSection() {
  const t = useTranslations('reports');
  const locale = useLocale();
  const currentYear = new Date().getFullYear();
  const [year, setYear] = useState(currentYear);
  const { state, reload, isRefreshing } = useApiResource(
    (signal) => api.exports.elster(year, signal),
    [year],
    { keepPreviousData: true },
  );

  return (
    <Card>
      <CardHeader
        title={t('expenses.title')}
        description={t('expenses.description')}
        icon="file-text"
        headingLevel={2}
        actions={
          <div style={{ display: 'flex', gap: '0.5rem', alignItems: 'flex-end' }}>
            <Input
              label={t('expenses.year')}
              type="number"
              min={2000}
              max={2100}
              value={year}
              onChange={(event) => setYear(Number(event.target.value) || currentYear)}
              style={{ width: '7rem' }}
            />
            <ExportButton
              label={t('expenses.csv')}
              filename={`elster-${year}.csv`}
              onExport={() => api.exports.elsterCsv(year)}
            />
          </div>
        }
      />
      <CardBody>
        {state.status === 'loading' ? <SkeletonCards count={4} /> : null}
        {state.status === 'error' ? (
          <InlineAlert tone="danger">
            {t('expenses.error')}{' '}
            <button type="button" onClick={reload} disabled={isRefreshing}>
              {t('expenses.retry')}
            </button>
          </InlineAlert>
        ) : null}
        {state.status === 'ready' ? (
          <>
            <div
              style={{
                display: 'grid',
                gridTemplateColumns: 'repeat(auto-fit, minmax(10rem, 1fr))',
                gap: '0.75rem',
                marginBottom: '1rem',
              }}
            >
              {[
                { label: t('expenses.totals.expensesNetto'), value: state.data.summary.expensesNetto },
                { label: t('expenses.totals.expensesUst'), value: state.data.summary.expensesUst },
                { label: t('expenses.totals.expensesBrutto'), value: state.data.summary.expensesBrutto },
                { label: t('expenses.totals.vorsteuer'), value: state.data.summary.vorsteuerAbziehbar },
              ].map((item) => (
                <div key={item.label} style={{ border: '1px solid var(--ds-border)', borderRadius: 12, padding: '0.75rem' }}>
                  <p style={{ fontSize: '0.75rem', opacity: 0.75 }}>{item.label}</p>
                  <p style={{ fontSize: '1.25rem', fontWeight: 600 }}>{formatNumber(item.value, locale)}</p>
                </div>
              ))}
            </div>

            {state.data.summary.rows === 0 ? (
              <EmptyState
                icon="file-text"
                title={t('expenses.empty.title')}
                description={t('expenses.empty.description')}
              />
            ) : (
              <div style={{ overflowX: 'auto' }}>
                <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '0.875rem' }}>
                  <thead>
                    <tr>
                      {[t('expenses.columns.category'), t('expenses.columns.line'), t('expenses.columns.kz'), t('expenses.columns.netto'), t('expenses.columns.ust'), t('expenses.columns.brutto'), t('expenses.columns.date')].map((label) => (
                        <th key={label} scope="col" style={{ textAlign: 'left', padding: '0.5rem', borderBottom: '1px solid var(--ds-border)' }}>
                          {label}
                        </th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {state.data.rows.slice(0, 100).map((row) => (
                      <tr key={row.documentId}>
                        <td style={{ padding: '0.5rem', borderBottom: '1px solid var(--ds-border)' }}>{row.categoryCode ?? '—'}</td>
                        <td style={{ padding: '0.5rem', borderBottom: '1px solid var(--ds-border)' }}>{row.euerLine ?? '—'}</td>
                        <td style={{ padding: '0.5rem', borderBottom: '1px solid var(--ds-border)' }}>{row.euerKz ?? '—'}</td>
                        <td style={{ padding: '0.5rem', borderBottom: '1px solid var(--ds-border)' }}>{formatNumber(row.netto, locale)}</td>
                        <td style={{ padding: '0.5rem', borderBottom: '1px solid var(--ds-border)' }}>{formatNumber(row.ust, locale)}</td>
                        <td style={{ padding: '0.5rem', borderBottom: '1px solid var(--ds-border)' }}>{formatNumber(row.brutto, locale)}</td>
                        <td style={{ padding: '0.5rem', borderBottom: '1px solid var(--ds-border)' }}>{row.date}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                {state.data.rows.length > 100 ? (
                  <p style={{ marginTop: '0.5rem', fontSize: '0.75rem', opacity: 0.75 }}>{t('expenses.more', { count: state.data.rows.length - 100 })}</p>
                ) : null}
              </div>
            )}

            {state.data.summary.warnings.length > 0 ? (
              <div style={{ marginTop: '1rem' }}>
                <InlineAlert tone="warning">
                  <strong>{t('expenses.warnings.title')}</strong>
                  <ul style={{ margin: '0.5rem 0 0', paddingLeft: '1.25rem' }}>
                    {state.data.summary.warnings.slice(0, 10).map((warning) => (
                      <li key={`${warning.code}-${warning.documentId ?? ''}`}>{warning.message}</li>
                    ))}
                  </ul>
                </InlineAlert>
              </div>
            ) : null}
          </>
        ) : null}
      </CardBody>
    </Card>
  );
}
