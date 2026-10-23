'use client';

import Link from 'next/link';
import { useState } from 'react';
import { PageHeader, ResourceView } from '@/components/app/resource-view';
import { Badge, Card, CardBody, EmptyState, InlineAlert } from '@/components/ui';
import { Textarea } from '@/components/ui/form';
import { Button } from '@/components/ui/button';
import { SkeletonText } from '@/components/ui/feedback';
import { useToast } from '@/components/ui/toast';
import { formatDate } from '@/i18n/format';
import { useLocale, useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { toApiError } from '@/lib/api/errors';
import { useApiResource } from '@/lib/api/hooks';
import type { ApprovalRequestItem } from '@/lib/api/types';

type MaterialLabel = 'subject' | 'excerpt' | 'to' | 'attachments';

/** What the backend names the fields of the material, mapped to the labels above. */
function materialRows(item: ApprovalRequestItem): { readonly label: MaterialLabel; readonly value: string }[] {
  const m = item.material as Record<string, unknown>;
  const text = (key: string): string | null => {
    const value = m[key];
    return value === null || value === undefined || value === '' ? null : String(value);
  };
  const rows: { label: MaterialLabel; value: string | null }[] = [
    { label: 'subject', value: text('subject') },
    { label: 'to', value: text('to_addresses')?.replace(/[[\]"]/g, '') ?? null },
    { label: 'attachments', value: text('attachments') && text('attachments') !== '0' ? text('attachments') : null },
    { label: 'excerpt', value: text('excerpt') },
  ];
  return rows.filter((row): row is { label: MaterialLabel; value: string } => row.value !== null);
}

/** The approval centre: each open request with the material it is about, the case, the warnings and the term. */
export default function ApprovalsPage() {
  const t = useTranslations('approvals');
  const tNav = useTranslations('nav');
  const tErrors = useTranslations('errors');
  const locale = useLocale();
  const toast = useToast();
  const { state, reload, isRefreshing } = useApiResource((signal) => api.approvals.center(signal), []);
  const [notes, setNotes] = useState<Record<string, string>>({});
  const [problem, setProblem] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState<string | null>(null);

  const decide = async (item: ApprovalRequestItem, kind: 'approve' | 'reject' | 'changes'): Promise<void> => {
    const note = (notes[item.id] ?? '').trim();
    if (kind !== 'approve' && note.length === 0) {
      setProblem((current) => ({ ...current, [item.id]: t('note.required') }));
      return;
    }
    setProblem((current) => ({ ...current, [item.id]: '' }));
    setBusy(item.id);
    try {
      await api.approvals.decide(item.id, kind === 'approve' ? { kind, note: note || undefined } : { kind, note });
      toast.success(t('done'));
      reload();
    } catch (error) {
      toast.error(tErrors(toApiError(error).kind));
      reload();
    } finally {
      setBusy(null);
    }
  };

  return (
    <>
      <PageHeader title={tNav('items.approvals')} subtitle={t('subtitle')} />
      <ResourceView
        state={state}
        onRetry={reload}
        isRefreshing={isRefreshing}
        isEmpty={(items) => items.length === 0}
        emptyState={<EmptyState icon="check-circle" title={t('empty.title')} description={t('empty.description')} />}
        skeleton={
          <Card>
            <CardBody>
              <SkeletonText lines={6} />
            </CardBody>
          </Card>
        }
      >
        {(items) => (
          <ul style={{ listStyle: 'none', padding: 0, display: 'grid', gap: '1rem' }}>
            {items.map((item) => (
              <li key={item.id} data-testid="approval">
                <Card>
                  <CardBody>
                    <h2>{item.title}</h2>
                    <p>
                      {item.case ? (
                        <>
                          {t('case')}: <Link href={`/cases/${item.case.id}`}>{item.case.reference} · {item.case.title}</Link>{' · '}
                        </>
                      ) : null}
                      {item.requestedByName ? t('requestedBy', { name: item.requestedByName }) : null}
                      {item.expiresAt ? ` · ${t('expires', { date: formatDate(item.expiresAt, locale) })}` : null}
                    </p>
                    {item.warnings.map((warning) => (
                      <InlineAlert key={warning} tone="warning">
                        {t(`warning.${warning}` as 'warning.OPEN_RADAR_FINDINGS')}
                      </InlineAlert>
                    ))}
                    {item.justification ? (
                      <p>
                        <strong>{t('justification')}:</strong> {item.justification}
                      </p>
                    ) : null}
                    <dl style={{ display: 'grid', gridTemplateColumns: 'max-content 1fr', gap: '0.25rem 1rem' }}>
                      {materialRows(item).map((row) => (
                        <div key={row.label} style={{ display: 'contents' }}>
                          <dt>{t(`material.${row.label}` as 'material.subject')}</dt>
                          <dd style={{ margin: 0, whiteSpace: 'pre-wrap' }}>{row.value}</dd>
                        </div>
                      ))}
                    </dl>
                    {item.bound ? <p><Badge tone="info">{t('bound')}</Badge></p> : null}
                    {item.mayDecide ? (
                      <>
                        <Textarea
                          label={t('note.label')}
                          rows={2}
                          value={notes[item.id] ?? ''}
                          onChange={(event) => setNotes((current) => ({ ...current, [item.id]: event.target.value }))}
                          error={problem[item.id] || undefined}
                        />
                        <div style={{ display: 'flex', flexWrap: 'wrap', gap: '0.5rem', marginTop: '0.5rem' }}>
                          <Button loading={busy === item.id} onClick={() => void decide(item, 'approve')}>
                            {t('action.approve')}
                          </Button>
                          <Button variant="secondary" loading={busy === item.id} onClick={() => void decide(item, 'changes')}>
                            {t('action.changes')}
                          </Button>
                          <Button variant="danger" loading={busy === item.id} onClick={() => void decide(item, 'reject')}>
                            {t('action.reject')}
                          </Button>
                        </div>
                      </>
                    ) : (
                      <p>{t('cannotDecide')}</p>
                    )}
                  </CardBody>
                </Card>
              </li>
            ))}
          </ul>
        )}
      </ResourceView>
    </>
  );
}
