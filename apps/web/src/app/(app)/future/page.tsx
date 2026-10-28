'use client';

import Link from 'next/link';
import { useMemo, useState } from 'react';
import { PageHeader, ResourceView } from '@/components/app/resource-view';
import { Badge, Card, CardBody, CardHeader, EmptyState } from '@/components/ui';
import { Button } from '@/components/ui/button';
import { Input, Select } from '@/components/ui/form';
import { SkeletonText } from '@/components/ui/feedback';
import { useToast } from '@/components/ui/toast';
import { formatDate } from '@/i18n/format';
import { useLocale, useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { toApiError } from '@/lib/api/errors';
import { useApiResource } from '@/lib/api/hooks';
import type { FutureEvent, ScenarioResult, ScenarioType } from '@/lib/api/types';

const HORIZONS = [30, 60, 90] as const;
const KINDS = ['DEADLINE', 'TASK', 'OBLIGATION', 'CONTRACT_NOTICE', 'CONTRACT_END', 'INVOICE_DUE', 'CASE_DUE', 'EXPECTED_REPLY'] as const;
const SCENARIOS: readonly ScenarioType[] = ['NOTICE_MISSED', 'PRICE_CHANGE', 'PAYMENT_LATE'];

/** Where an event leads: the document it was read from, else the contract, the invoice or the case it belongs to. */
function href(event: FutureEvent): string | null {
  if (event.documentId) return `/documents/${event.documentId}`;
  if (event.source.type === 'INVOICE') return `/invoices/${event.source.id}`;
  if (event.contractId) return `/contracts/${event.contractId}`;
  if (event.caseId) return `/cases/${event.caseId}`;
  return null;
}

function EventRow({ event }: { readonly event: FutureEvent }) {
  const t = useTranslations('future');
  const locale = useLocale();
  const target = href(event);
  return (
    <li data-testid="future-event">
      {event.date ? <time dateTime={event.date}>{formatDate(event.date, locale)}</time> : <span>{t('noDate')}</span>}{' '}
      <Badge tone="neutral">{t(`kind.${event.kind}` as 'kind.DEADLINE')}</Badge>{' '}
      {target ? <Link href={target}>{event.title}</Link> : event.title}
      {event.amount !== null && event.amount !== undefined ? ` · ${event.amount} ${event.currency ?? ''}` : ''}
      {event.certainty === 'UNCONFIRMED' ? (
        <>
          {' '}
          <Badge tone="warning">{t('unconfirmed')}</Badge>
        </>
      ) : null}
      {event.unknown.length > 0 ? (
        <ul>
          {event.unknown.map((note) => (
            <li key={note}>
              <small>
                {t('unknown')}: {note}
              </small>
            </li>
          ))}
        </ul>
      ) : null}
    </li>
  );
}

function ScenarioPanel() {
  const t = useTranslations('future');
  const tErrors = useTranslations('errors');
  const toast = useToast();
  const contracts = useApiResource((signal) => api.contracts.list({ size: 100 }, signal), []);
  const invoices = useApiResource((signal) => api.invoices.list({ size: 100 }, signal), []);
  const [type, setType] = useState<ScenarioType>('NOTICE_MISSED');
  const [contractId, setContractId] = useState('');
  const [invoiceId, setInvoiceId] = useState('');
  const [percent, setPercent] = useState('');
  const [days, setDays] = useState('');
  const [result, setResult] = useState<ScenarioResult | null>(null);
  const [busy, setBusy] = useState(false);

  const run = async (): Promise<void> => {
    setBusy(true);
    try {
      setResult(
        await api.memory.scenario({
          type,
          contractId: type === 'PAYMENT_LATE' ? undefined : contractId || undefined,
          invoiceId: type === 'PAYMENT_LATE' ? invoiceId || undefined : undefined,
          percent: type === 'PRICE_CHANGE' && percent !== '' ? Number(percent) : undefined,
          days: type === 'PAYMENT_LATE' && days !== '' ? Number(days) : undefined,
        }),
      );
    } catch (error) {
      toast.error(tErrors(toApiError(error).kind));
    } finally {
      setBusy(false);
    }
  };

  const contractOptions = contracts.state.status === 'ready' ? contracts.state.data.items : [];
  const invoiceOptions = invoices.state.status === 'ready' ? invoices.state.data.items : [];
  const entries = (record: Readonly<Record<string, unknown>> | undefined): [string, string][] =>
    Object.entries(record ?? {}).map(([key, value]) => [key, Array.isArray(value) ? value.join('; ') : String(value)]);

  return (
    <Card>
      <CardHeader title={t('scenario.title')} />
      <CardBody>
        <p role="note">{t('scenario.readOnly')}</p>
        <Select
          label={t('scenario.type')}
          value={type}
          onChange={(event) => {
            setType(event.target.value as ScenarioType);
            setResult(null);
          }}
        >
          {SCENARIOS.map((item) => (
            <option key={item} value={item}>
              {t(`scenario.type.${item}` as 'scenario.type.NOTICE_MISSED')}
            </option>
          ))}
        </Select>
        {type === 'PAYMENT_LATE' ? (
          <>
            <Select label={t('scenario.invoice')} value={invoiceId} onChange={(event) => setInvoiceId(event.target.value)}>
              <option value="">—</option>
              {invoiceOptions.map((invoice) => (
                <option key={invoice.id} value={invoice.id}>
                  {invoice.number} · {invoice.counterparty}
                </option>
              ))}
            </Select>
            <Input label={t('scenario.days')} type="number" min={1} value={days} onChange={(event) => setDays(event.target.value)} />
          </>
        ) : (
          <>
            <Select label={t('scenario.contract')} value={contractId} onChange={(event) => setContractId(event.target.value)}>
              <option value="">—</option>
              {contractOptions.map((contract) => (
                <option key={contract.id} value={contract.id}>
                  {contract.partner}
                </option>
              ))}
            </Select>
            {type === 'PRICE_CHANGE' ? (
              <Input label={t('scenario.percent')} type="number" value={percent} onChange={(event) => setPercent(event.target.value)} />
            ) : null}
          </>
        )}
        <Button variant="secondary" loading={busy} onClick={() => void run()}>
          {t('scenario.run')}
        </Button>

        <div aria-live="polite" data-testid="scenario-result">
          {result?.status === 'INSUFFICIENT_DATA' ? (
            <div role="status">
              <strong>{t('scenario.insufficient')}</strong>
              <ul>
                {(result.missing ?? []).map((item) => (
                  <li key={item}>{item}</li>
                ))}
              </ul>
            </div>
          ) : null}
          {result?.status === 'COMPUTED' ? (
            <div>
              <h3>{t('scenario.baseline')}</h3>
              <dl>
                {entries(result.baseline).map(([key, value]) => (
                  <div key={key}>
                    <dt>{key}</dt>
                    <dd>{value}</dd>
                  </div>
                ))}
              </dl>
              <h3>{t('scenario.alternative')}</h3>
              <dl>
                {entries(result.alternative).map(([key, value]) => (
                  <div key={key}>
                    <dt>{key}</dt>
                    <dd>{value}</dd>
                  </div>
                ))}
              </dl>
              {result.difference !== undefined ? (
                <p>
                  {t('scenario.difference')}: {String(result.difference)} {result.currency ?? ''}
                </p>
              ) : null}
              {(result.assumptions ?? []).map((item) => (
                <p key={item}>
                  <small>
                    {t('scenario.assumption')}: {item}
                  </small>
                </p>
              ))}
              {(result.unknown ?? []).map((item) => (
                <p key={item}>
                  <small>
                    {t('unknown')}: {item}
                  </small>
                </p>
              ))}
            </div>
          ) : null}
        </div>
      </CardBody>
    </Card>
  );
}

/** The Future Timeline: what is recorded to happen in the next 30, 60 or 90 days. What is not known is said, not guessed. */
export default function FuturePage() {
  const t = useTranslations('future');
  const tNav = useTranslations('nav');
  const [horizon, setHorizon] = useState<30 | 60 | 90>(30);
  const [kind, setKind] = useState('');
  const locale = useLocale();
  const { state, reload, isRefreshing } = useApiResource((signal) => api.memory.future(horizon, signal), [horizon], {
    keepPreviousData: true,
  });

  const groups = useMemo(() => {
    if (state.status !== 'ready') return [] as [string, FutureEvent[]][];
    const by = new Map<string, FutureEvent[]>();
    for (const event of state.data.events) {
      if (kind && event.kind !== kind) continue;
      const key = (event.date ?? '').slice(0, 7);
      by.set(key, [...(by.get(key) ?? []), event]);
    }
    return [...by.entries()];
  }, [state, kind]);

  return (
    <>
      <PageHeader title={tNav('items.future')} subtitle={t('subtitle')} />
      <div role="group" aria-label={t('horizon')} style={{ display: 'flex', gap: '0.5rem', flexWrap: 'wrap', marginBottom: '0.75rem' }}>
        {HORIZONS.map((item) => (
          <Button key={item} variant={horizon === item ? 'primary' : 'secondary'} aria-pressed={horizon === item} onClick={() => setHorizon(item)}>
            {t('days', { count: item })}
          </Button>
        ))}
        <Select label={t('filter')} value={kind} onChange={(event) => setKind(event.target.value)}>
          <option value="">{t('allKinds')}</option>
          {KINDS.map((item) => (
            <option key={item} value={item}>
              {t(`kind.${item}` as 'kind.DEADLINE')}
            </option>
          ))}
        </Select>
      </div>
      <ResourceView
        state={state}
        onRetry={reload}
        isRefreshing={isRefreshing}
        isEmpty={(data) => data.events.length === 0 && data.undated.length === 0}
        emptyState={<EmptyState icon="calendar" title={t('empty.title')} description={t('empty.description')} />}
        skeleton={
          <Card>
            <CardBody>
              <SkeletonText lines={6} />
            </CardBody>
          </Card>
        }
      >
        {(data) => (
          <>
            <p>
              {formatDate(data.from, locale)} – {formatDate(data.to, locale)}
            </p>
            {groups.length === 0 ? <p>{t('nothingInRange')}</p> : null}
            {groups.map(([month, events]) => (
              <section key={month} aria-label={month}>
                <h2>{month}</h2>
                <ul style={{ listStyle: 'none', padding: 0, display: 'grid', gap: '0.5rem' }}>
                  {events.map((event) => (
                    <EventRow key={event.id} event={event} />
                  ))}
                </ul>
              </section>
            ))}
            {data.undated.length > 0 ? (
              <section aria-labelledby="undated-heading">
                <h2 id="undated-heading">{t('undated')}</h2>
                <p>{t('undatedHint')}</p>
                <ul style={{ listStyle: 'none', padding: 0, display: 'grid', gap: '0.5rem' }}>
                  {data.undated.map((event) => (
                    <EventRow key={event.id} event={event} />
                  ))}
                </ul>
              </section>
            ) : null}
          </>
        )}
      </ResourceView>
      <ScenarioPanel />
    </>
  );
}
