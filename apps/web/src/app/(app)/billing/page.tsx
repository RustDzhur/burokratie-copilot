'use client';

import { useRouter } from 'next/navigation';
import { useState, type FormEvent } from 'react';
import { PageHeader, PageSection, ResourceView } from '@/components/app/resource-view';
import { Card, CardBody, CardHeader, StatusPill } from '@/components/ui';
import { Button } from '@/components/ui/button';
import { InlineAlert } from '@/components/ui/feedback';
import { Input, Select, Textarea } from '@/components/ui/form';
import { Dialog } from '@/components/ui/overlay';
import { useToast } from '@/components/ui/toast';
import { formatDate } from '@/i18n/format';
import { useLocale, useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { toApiError } from '@/lib/api/errors';
import { useApiResource } from '@/lib/api/hooks';
import type { BillingInvoice, BillingState, InvoiceRequest } from '@/lib/api/types';
import styles from './billing.module.css';
import { QrCode } from './qr-code';

const MONTHS = [1, 3, 6, 12] as const;

const euros = (cents: number): string => (cents / 100).toFixed(2).replace('.', ',');

/** Plan, payment and invoices of the workspace; also where the owner can delete the account. */
export default function BillingPage() {
  const t = useTranslations('billing');
  const tErrors = useTranslations('errors');
  const tNav = useTranslations('nav');
  const locale = useLocale();
  const toast = useToast();
  const router = useRouter();

  const { state, reload } = useApiResource<BillingState>((signal) => api.billing.state(signal), []);
  const { state: invoiceState, reload: reloadInvoices } = useApiResource<readonly BillingInvoice[]>(
    (signal) => api.billing.invoices(signal).catch(() => []),
    [],
  );

  const [method, setMethod] = useState<InvoiceRequest['method']>('INVOICE');
  const [months, setMonths] = useState(1);
  const [name, setName] = useState('');
  const [address, setAddress] = useState('');
  const [country, setCountry] = useState('DE');
  const [vatId, setVatId] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [deleteWord, setDeleteWord] = useState('');

  const changePlan = async (code: string): Promise<void> => {
    try {
      await api.billing.changePlan(code);
      reload();
    } catch (caught) {
      toast.error(t('change.title'), tErrors(toApiError(caught).kind));
    }
  };

  const createInvoice = async (event: FormEvent): Promise<void> => {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await api.billing.createInvoice({
        method,
        months,
        customerName: name,
        customerAddress: address,
        customerCountry: country,
        customerVatId: vatId || undefined,
      });
      toast.success(t('pay.created'));
      reloadInvoices();
    } catch (caught) {
      setError(tErrors(toApiError(caught).kind));
    } finally {
      setBusy(false);
    }
  };

  const deleteEverything = async (): Promise<void> => {
    try {
      await api.billing.deleteData();
      toast.success(t('data.deleted'));
      router.replace('/login');
    } catch (caught) {
      toast.error(t('data.delete'), tErrors(toApiError(caught).kind));
    }
  };

  return (
    <>
      <PageHeader title={tNav('items.billing')} subtitle={t('subtitle')} />
      <ResourceView state={state} onRetry={reload} isEmpty={() => false} emptyState={null}>
        {(data) => (
          <>
            <PageSection title={t('plan.title')}>
              <Card>
                <CardHeader
                  title={data.planName}
                  description={
                    data.status === 'TRIAL' ? t('trialInfo') : t('until', { date: formatDate(data.accessUntil, locale) })
                  }
                  icon="credit-card"
                  badge={
                    <StatusPill
                      label={t(`status.${data.status}` as 'status.TRIAL')}
                      status={data.status === 'ACTIVE' ? 'connected' : data.status === 'TRIAL' ? 'pending' : 'error'}
                    />
                  }
                />
                <CardBody>
                  <p className={styles.muted}>
                    {data.status === 'TRIAL' ? t('until', { date: formatDate(data.accessUntil, locale) }) : null}{' '}
                    {t('daysLeft', { days: data.daysLeft })}
                  </p>
                  <p className={styles.muted}>{t('retention', { days: data.retentionDays })}</p>
                </CardBody>
              </Card>
            </PageSection>

            <PageSection title={t('change.title')}>
              <p className={styles.muted}>{t('change.hint')}</p>
              <ul className={styles.plans}>
                {data.plans.map((plan) => (
                  <li key={plan.code}>
                    <Card>
                      <CardBody>
                        <div className={styles.plan}>
                          <h3 className={styles.planName}>{plan.name}</h3>
                          <p className={styles.planPrice}>{t('plan.price', { price: euros(plan.priceCents) })}</p>
                          {plan.code === data.planCode ? (
                            <StatusPill label={t('change.current')} status="connected" />
                          ) : (
                            <Button
                              variant="secondary"
                              disabled={!data.owner}
                              onClick={() => void changePlan(plan.code)}
                            >
                              {t('change.action')}
                            </Button>
                          )}
                        </div>
                      </CardBody>
                    </Card>
                  </li>
                ))}
              </ul>
            </PageSection>

            <PageSection title={t('pay.title')}>
              <p className={styles.muted}>{t('pay.description')}</p>
              {!data.owner ? (
                <InlineAlert tone="info">{t('pay.notOwner')}</InlineAlert>
              ) : (
                <form className={styles.form} onSubmit={(event) => void createInvoice(event)}>
                  {error ? <InlineAlert tone="danger" title={error} /> : null}
                  <Select
                    label={t('pay.method')}
                    value={method}
                    onChange={(event) => setMethod(event.target.value as InvoiceRequest['method'])}
                    options={[
                      { value: 'INVOICE', label: t('pay.method.INVOICE') },
                      { value: 'CRYPTO', label: t('pay.method.CRYPTO') },
                    ]}
                  />
                  {(method === 'INVOICE' && !data.invoiceAvailable) || (method === 'CRYPTO' && !data.cryptoAvailable) ? (
                    <InlineAlert tone="warning">{t('pay.unavailable')}</InlineAlert>
                  ) : null}
                  <Select
                    label={t('pay.months')}
                    value={String(months)}
                    onChange={(event) => setMonths(Number(event.target.value))}
                    options={MONTHS.map((value) => ({ value: String(value), label: String(value) }))}
                  />
                  <Input label={t('pay.name')} value={name} required onChange={(event) => setName(event.target.value)} />
                  <Textarea
                    label={t('pay.address')}
                    value={address}
                    required
                    rows={3}
                    onChange={(event) => setAddress(event.target.value)}
                  />
                  <Input
                    label={t('pay.country')}
                    value={country}
                    required
                    maxLength={2}
                    onChange={(event) => setCountry(event.target.value.toUpperCase())}
                  />
                  <Input label={t('pay.vat')} value={vatId} onChange={(event) => setVatId(event.target.value)} />
                  <div className={styles.actions}>
                    <Button type="submit" variant="primary" loading={busy}>
                      {t('pay.submit')}
                    </Button>
                  </div>
                </form>
              )}
            </PageSection>
          </>
        )}
      </ResourceView>

      <PageSection title={t('invoice.title')}>
        <ResourceView
          state={invoiceState}
          onRetry={reloadInvoices}
          isEmpty={(items) => items.length === 0}
          emptyState={<p className={styles.muted}>{t('invoice.empty')}</p>}
        >
          {(items) => (
            <div className={styles.invoice}>
              {items.map((invoice) => (
                <InvoiceCard key={invoice.id} invoice={invoice} />
              ))}
            </div>
          )}
        </ResourceView>
      </PageSection>

      <PageSection title={t('data.title')}>
        <p className={styles.muted}>{t('data.description')}</p>
        <div className={styles.actions}>
          <Button variant="danger" onClick={() => setDeleteOpen(true)}>
            {t('data.delete')}
          </Button>
        </div>
      </PageSection>

      <Dialog
        open={deleteOpen}
        onClose={() => setDeleteOpen(false)}
        title={t('data.deleteTitle')}
        description={t('data.deleteBody')}
        footer={
          <Button variant="danger" disabled={deleteWord !== 'DELETE'} onClick={() => void deleteEverything()}>
            {t('data.delete')}
          </Button>
        }
      >
        <Input label={t('data.deleteConfirm')} value={deleteWord} onChange={(event) => setDeleteWord(event.target.value)} />
      </Dialog>
    </>
  );
}

function InvoiceCard({ invoice }: { readonly invoice: BillingInvoice }) {
  const t = useTranslations('billing');
  const locale = useLocale();
  return (
    <Card>
      <CardHeader
        title={`${invoice.number} · ${euros(invoice.amountCents)} €`}
        description={t('invoice.dueOn', { date: formatDate(invoice.dueAt, locale) })}
        badge={
          <StatusPill
            label={t(`invoice.status.${invoice.status}` as 'invoice.status.PAID')}
            status={invoice.status === 'PAID' ? 'connected' : invoice.status === 'ISSUED' ? 'pending' : 'disconnected'}
          />
        }
      />
      <CardBody>
        {invoice.status === 'ISSUED' ? (
          invoice.method === 'CRYPTO' && invoice.cryptoAddress ? (
            <div className={styles.invoice}>
              <p>{t('invoice.crypto.amount', { amount: invoice.cryptoAmount ?? '', asset: invoice.cryptoAsset ?? '' })}</p>
              {invoice.cryptoNetwork ? <p>{t('invoice.crypto.network', { network: invoice.cryptoNetwork })}</p> : null}
              <p className={styles.mono}>
                {t('invoice.crypto.address')}: {invoice.cryptoAddress}
              </p>
              <QrCode text={invoice.cryptoAddress} label={t('invoice.crypto.address')} />
              <p className={styles.muted}>{t('invoice.crypto.scan')}</p>
            </div>
          ) : (
            <p>{t('invoice.reference', { reference: invoice.reference })}</p>
          )
        ) : null}
        <div className={styles.actions}>
          <a href={api.billing.invoicePdfUrl(invoice.id)} target="_blank" rel="noopener">
            {t('invoice.pdf')}
          </a>
        </div>
      </CardBody>
    </Card>
  );
}
