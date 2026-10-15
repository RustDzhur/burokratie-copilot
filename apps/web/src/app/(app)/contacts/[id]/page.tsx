'use client';

import Link from 'next/link';
import { useParams } from 'next/navigation';
import { useState } from 'react';
import { PageHeader, PageSection, ResourceView } from '@/components/app/resource-view';
import { Badge, Card, CardBody } from '@/components/ui';
import { Input } from '@/components/ui/form';
import { Button } from '@/components/ui/button';
import { buttonClass } from '@/components/ui/button-class';
import { SkeletonText } from '@/components/ui/feedback';
import { useToast } from '@/components/ui/toast';
import { formatDate, formatDateTime } from '@/i18n/format';
import { useLocale, useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { toApiError } from '@/lib/api/errors';
import { useApiResource } from '@/lib/api/hooks';

const KINDS = ['VAT_ID', 'TAX_NUMBER', 'IBAN', 'EMAIL', 'DOMAIN', 'PHONE'] as const;

/** One contact with everything the memory holds about it: identifiers, documents, contracts, invoices, cases, findings, timeline. */
export default function ContactProfilePage() {
  const { id } = useParams<{ id: string }>();
  const t = useTranslations('contactProfile');
  const tErrors = useTranslations('errors');
  const locale = useLocale();
  const toast = useToast();
  const { state, reload, isRefreshing } = useApiResource((signal) => api.memory.profile(id, signal), [id]);
  const timeline = useApiResource((signal) => api.memory.contactTimeline(id, signal), [id]);
  const [kind, setKind] = useState<(typeof KINDS)[number]>('IBAN');
  const [value, setValue] = useState('');
  const [busy, setBusy] = useState(false);

  const run = async (work: () => Promise<void>): Promise<void> => {
    setBusy(true);
    try {
      await work();
      reload();
    } catch (error) {
      toast.error(tErrors(toApiError(error).kind));
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <PageHeader
        title={state.status === 'ready' ? state.data.contact.name : t('back')}
        subtitle={state.status === 'ready' ? [state.data.contact.email, state.data.contact.phone, state.data.contact.isCustomer ? t('customer') : null, state.data.contact.isSupplier ? t('supplier') : null].filter(Boolean).join(' · ') : undefined}
        actions={
          <Link href="/contacts" className={buttonClass({ variant: 'secondary' })}>
            {t('back')}
          </Link>
        }
      />
      <ResourceView
        state={state}
        onRetry={reload}
        isRefreshing={isRefreshing}
        isEmpty={() => false}
        emptyState={null}
        skeleton={
          <Card>
            <CardBody>
              <SkeletonText lines={6} />
            </CardBody>
          </Card>
        }
      >
        {(profile) => (
          <>
            <PageSection title={t('identifiers')}>
              <p>{t('identifiersHint')}</p>
              {profile.identifiers.length === 0 ? <p>{t('noIdentifiers')}</p> : null}
              <ul style={{ listStyle: 'none', padding: 0, display: 'grid', gap: '0.5rem' }}>
                {profile.identifiers.map((identifier) => (
                  <li key={identifier.id}>
                    <Badge tone="neutral">{identifier.kind}</Badge> {identifier.value}{' '}
                    <Button variant="secondary" loading={busy} onClick={() => void run(() => api.memory.removeIdentifier(id, identifier.id))}>
                      {t('remove')}
                    </Button>
                  </li>
                ))}
              </ul>
              <Card>
                <CardBody>
                  <label>
                    {t('kind')}{' '}
                    <select value={kind} onChange={(event) => setKind(event.target.value as (typeof KINDS)[number])}>
                      {KINDS.map((option) => (
                        <option key={option} value={option}>
                          {option}
                        </option>
                      ))}
                    </select>
                  </label>
                  <Input label={t('value')} value={value} onChange={(event) => setValue(event.target.value)} />
                  <Button
                    variant="secondary"
                    loading={busy}
                    disabled={value.trim().length === 0}
                    onClick={() => void run(async () => { await api.memory.addIdentifier(id, kind, value.trim()); setValue(''); })}
                  >
                    {t('addIdentifier')}
                  </Button>
                </CardBody>
              </Card>
            </PageSection>

            <PageSection title={t('documents')}>
              {profile.documents.length === 0 ? <p>{t('noDocuments')}</p> : null}
              <ul style={{ listStyle: 'none', padding: 0, display: 'grid', gap: '0.5rem' }}>
                {profile.documents.map((document) => (
                  <li key={document.relationshipId} data-testid="relationship">
                    <Link href={`/documents/${document.documentId}`}>{document.name}</Link>{' '}
                    <Badge tone={document.status === 'CONFIRMED' ? 'success' : 'warning'}>{t(`status.${document.status}` as 'status.CONFIRMED')}</Badge>{' '}
                    {document.status === 'SUGGESTED' ? (
                      <>
                        <Button variant="secondary" loading={busy} onClick={() => void run(() => api.memory.confirmRelationship(document.relationshipId))}>
                          {t('confirm')}
                        </Button>{' '}
                        <Button variant="secondary" loading={busy} onClick={() => void run(() => api.memory.rejectRelationship(document.relationshipId))}>
                          {t('reject')}
                        </Button>
                      </>
                    ) : null}
                  </li>
                ))}
              </ul>
            </PageSection>

            <PageSection title={t('contracts')}>
              {profile.contracts.length === 0 ? <p>{t('none')}</p> : (
                <ul>{profile.contracts.map((contract) => (
                  <li key={contract.id}>{contract.title} · {contract.status}{contract.noticeDeadline ? ` · ${t('noticeDeadline', { date: formatDate(contract.noticeDeadline, locale) })}` : ''}</li>
                ))}</ul>
              )}
            </PageSection>
            <PageSection title={t('invoices')}>
              {profile.invoices.length === 0 ? <p>{t('none')}</p> : (
                <ul>{profile.invoices.map((invoice) => (
                  <li key={invoice.id}>{invoice.number ?? '—'} · {invoice.gross ?? '—'} {invoice.currency} · {invoice.status}</li>
                ))}</ul>
              )}
            </PageSection>
            <PageSection title={t('cases')}>
              {profile.cases.length === 0 ? <p>{t('none')}</p> : (
                <ul>{profile.cases.map((item) => (
                  <li key={item.id}><Link href={`/cases/${item.id}`}>{item.reference} · {item.title}</Link> · {item.status}</li>
                ))}</ul>
              )}
            </PageSection>
            {profile.openFindings.length > 0 ? (
              <PageSection title={t('findings')}>
                <ul>{profile.openFindings.map((finding) => (
                  <li key={finding.id}><Link href={`/radar/${finding.id}`}>{finding.title}</Link> · {finding.severity}</li>
                ))}</ul>
              </PageSection>
            ) : null}

            <PageSection title={t('timeline')}>
              {timeline.state.status === 'ready' && timeline.state.data.length > 0 ? (
                <ul style={{ listStyle: 'none', padding: 0, display: 'grid', gap: '0.5rem' }}>
                  {timeline.state.data.map((entry) => (
                    <li key={`${entry.source}-${entry.id}`}>
                      <strong>{formatDateTime(entry.eventTime, locale)}</strong> — {entry.title} <small>({entry.source}, {t('recorded', { date: formatDate(entry.recordedAt, locale) })})</small>
                    </li>
                  ))}
                </ul>
              ) : (
                <p>{t('noTimeline')}</p>
              )}
            </PageSection>
          </>
        )}
      </ResourceView>
    </>
  );
}
