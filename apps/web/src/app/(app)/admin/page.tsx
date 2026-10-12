'use client';

import { useState, type FormEvent } from 'react';
import { PageHeader, ResourceView } from '@/components/app/resource-view';
import { Card, CardBody, DataTable, StatusPill, Tabs } from '@/components/ui';
import type { Column } from '@/components/ui';
import { Button } from '@/components/ui/button';
import { InlineAlert } from '@/components/ui/feedback';
import { Input, Select, Textarea } from '@/components/ui/form';
import { useToast } from '@/components/ui/toast';
import { formatDate } from '@/i18n/format';
import { useLocale, useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { toApiError } from '@/lib/api/errors';
import { useApiResource } from '@/lib/api/hooks';
import { useSession } from '@/lib/auth/session-context';
import type {
  AdminOverview,
  AdminService,
  AdminTenant,
  BillingInvoice,
  PlanSummary,
  SubscriptionStatus,
} from '@/lib/api/types';
import styles from './admin.module.css';

const STATUSES: readonly SubscriptionStatus[] = ['TRIAL', 'ACTIVE', 'EXPIRED', 'SUSPENDED'];
const euros = (cents: number): string => (cents / 100).toFixed(2).replace('.', ',');

/**
 * The platform administrator's area. The server refuses every call here for anybody else; this page also stays
 * empty for them, so nobody sees a screen that would only answer "forbidden".
 */
export default function AdminPage() {
  const t = useTranslations('admin');
  const tNav = useTranslations('nav');
  const session = useSession();

  if (session && !session.platformAdmin) {
    return <InlineAlert tone="warning" title={tNav('items.admin')} />;
  }

  return (
    <>
      <PageHeader title={tNav('items.admin')} subtitle={t('subtitle')} />
      <Tabs
        label={tNav('items.admin')}
        defaultValue="overview"
        tabs={[
          { id: 'overview', label: t('tab.overview'), content: <OverviewTab /> },
          { id: 'customers', label: t('tab.customers'), content: <CustomersTab /> },
          { id: 'payments', label: t('tab.payments'), content: <PaymentsTab /> },
          { id: 'plans', label: t('tab.plans'), content: <PlansTab /> },
          { id: 'settings', label: t('tab.settings'), content: <SettingsTab /> },
          { id: 'services', label: t('tab.services'), content: <ServicesTab /> },
        ]}
      />
    </>
  );
}

function OverviewTab() {
  const t = useTranslations('admin');
  const tErrors = useTranslations('errors');
  const toast = useToast();
  const { state, reload } = useApiResource<AdminOverview>((signal) => api.admin.overview(signal), []);
  return (
    <ResourceView state={state} onRetry={reload} isEmpty={() => false} emptyState={null}>
      {(data) => (
        <Card>
          <CardBody>
            <dl className={styles.metrics}>
              <div>
                <dt>{t('overview.tenants')}</dt>
                <dd>{data.tenants}</dd>
              </div>
              <div>
                <dt>{t('overview.mrr')}</dt>
                <dd>{euros(data.monthlyRecurringCents)} €</dd>
              </div>
              <div>
                <dt>{t('overview.trialEnding')}</dt>
                <dd>{data.trialEndingSoon}</dd>
              </div>
              <div>
                <dt>{t('overview.openInvoices')}</dt>
                <dd>{data.openInvoices}</dd>
              </div>
              {data.byStatus.map((row) => (
                <div key={row.status}>
                  <dt>{row.status}</dt>
                  <dd>{row.count}</dd>
                </div>
              ))}
            </dl>
            <p className={styles.muted}>
              {t('overview.payee')}: {data.payeeConfigured ? t('overview.configured') : t('overview.missing')} ·{' '}
              {t('overview.crypto')}: {data.cryptoConfigured ? t('overview.configured') : t('overview.missing')}
            </p>
            <Button
              variant="secondary"
              onClick={() => {
                api.admin
                  .runLifecycle()
                  .then(() => toast.success(t('overview.lifecycleRan')))
                  .catch((caught: unknown) => toast.error(t('overview.runLifecycle'), tErrors(toApiError(caught).kind)));
              }}
            >
              {t('overview.runLifecycle')}
            </Button>
          </CardBody>
        </Card>
      )}
    </ResourceView>
  );
}

function CustomersTab() {
  const t = useTranslations('admin');
  const tErrors = useTranslations('errors');
  const toast = useToast();
  const locale = useLocale();
  const [query, setQuery] = useState('');
  const [status, setStatus] = useState('');
  const { state, reload } = useApiResource<readonly AdminTenant[]>(
    (signal) => api.admin.tenants({ q: query || undefined, status: status || undefined }, signal),
    [query, status],
  );

  const act = (action: () => Promise<void>): void => {
    action()
      .then(() => {
        toast.success(t('customers.done'));
        reload();
      })
      .catch((caught: unknown) => toast.error(t('tab.customers'), tErrors(toApiError(caught).kind)));
  };

  const columns: readonly Column<AdminTenant>[] = [
    { key: 'name', header: t('customers.name'), render: (row) => row.name },
    { key: 'owner', header: t('customers.owner'), render: (row) => row.ownerEmail ?? '' },
    { key: 'plan', header: t('customers.plan'), render: (row) => row.planCode },
    {
      key: 'status',
      header: t('customers.status'),
      render: (row) => (
        <StatusPill
          label={row.status}
          status={row.status === 'ACTIVE' ? 'connected' : row.status === 'TRIAL' ? 'pending' : 'error'}
        />
      ),
    },
    { key: 'access', header: t('customers.access'), render: (row) => formatDate(row.accessUntil, locale) },
    { key: 'members', header: t('customers.members'), render: (row) => String(row.members) },
    {
      key: 'actions',
      header: '',
      render: (row) => (
        <div className={styles.rowActions}>
          <Button size="sm" variant="secondary" onClick={() => act(() => api.admin.extend(row.id, 1))}>
            {t('customers.extend')}
          </Button>
          <Button size="sm" variant="ghost" onClick={() => act(() => api.admin.grantDays(row.id, 14))}>
            {t('customers.grant')}
          </Button>
          <Button
            size="sm"
            variant="ghost"
            onClick={() => act(() => api.admin.setSuspended(row.id, row.status !== 'SUSPENDED'))}
          >
            {row.status === 'SUSPENDED' ? t('customers.unsuspend') : t('customers.suspend')}
          </Button>
          <Button
            size="sm"
            variant="danger"
            onClick={() => {
              const typed = window.prompt(`${t('customers.deletePrompt')} ${row.name}`);
              if (typed !== null) act(() => api.admin.deleteTenant(row.id, typed));
            }}
          >
            {t('customers.delete')}
          </Button>
        </div>
      ),
    },
  ];

  return (
    <>
      <div className={styles.toolbar}>
        <Input label={t('customers.search')} value={query} onChange={(event) => setQuery(event.target.value)} />
        <Select
          label={t('customers.status')}
          value={status}
          onChange={(event) => setStatus(event.target.value)}
          placeholder="—"
          options={STATUSES.map((value) => ({ value, label: value }))}
        />
      </div>
      <ResourceView
        state={state}
        onRetry={reload}
        isEmpty={(rows) => rows.length === 0}
        emptyState={<p className={styles.muted}>{t('customers.empty')}</p>}
      >
        {(rows) => (
          <DataTable caption={t('tab.customers')} columns={columns} rows={rows} rowKey={(row) => row.id} />
        )}
      </ResourceView>
    </>
  );
}

function PaymentsTab() {
  const t = useTranslations('admin');
  const tBilling = useTranslations('billing');
  const tErrors = useTranslations('errors');
  const toast = useToast();
  const locale = useLocale();
  const [status, setStatus] = useState('ISSUED');
  const { state, reload } = useApiResource<readonly BillingInvoice[]>(
    (signal) => api.admin.invoices(status || undefined, signal),
    [status],
  );

  const run = (action: () => Promise<void>, message: string): void => {
    action()
      .then(() => {
        toast.success(message);
        reload();
      })
      .catch((caught: unknown) => toast.error(t('tab.payments'), tErrors(toApiError(caught).kind)));
  };

  const columns: readonly Column<BillingInvoice>[] = [
    { key: 'number', header: t('payments.number'), render: (row) => row.number },
    {
      key: 'customer',
      header: t('payments.customer'),
      render: (row) => `${row.tenantName ?? ''} ${row.customerEmail ? `(${row.customerEmail})` : ''}`,
    },
    {
      key: 'amount',
      header: t('payments.amount'),
      render: (row) =>
        row.method === 'CRYPTO' && row.cryptoAmount
          ? `${euros(row.amountCents)} € = ${row.cryptoAmount} ${row.cryptoAsset ?? ''}`
          : `${euros(row.amountCents)} €`,
    },
    { key: 'method', header: t('payments.method'), render: (row) => row.method },
    {
      key: 'status',
      header: t('payments.status'),
      render: (row) => (
        <StatusPill
          label={tBilling(`invoice.status.${row.status}` as 'invoice.status.PAID')}
          status={row.status === 'PAID' ? 'connected' : row.status === 'ISSUED' ? 'pending' : 'disconnected'}
        />
      ),
    },
    { key: 'due', header: tBilling('invoice.dueOn', { date: '' }), render: (row) => formatDate(row.dueAt, locale) },
    {
      key: 'actions',
      header: '',
      render: (row) => (
        <div className={styles.rowActions}>
          {row.status === 'ISSUED' ? (
            <>
              <Button size="sm" variant="primary" onClick={() => run(() => api.admin.markPaid(row.id), t('payments.confirmed'))}>
                {t('payments.markPaid')}
              </Button>
              <Button size="sm" variant="ghost" onClick={() => run(() => api.admin.cancelInvoice(row.id), t('customers.done'))}>
                {t('payments.cancel')}
              </Button>
            </>
          ) : null}
          <a href={api.admin.invoicePdfUrl(row.id)} target="_blank" rel="noopener">
            {t('payments.pdf')}
          </a>
        </div>
      ),
    },
  ];

  return (
    <>
      <div className={styles.toolbar}>
        <Select
          label={t('payments.status')}
          value={status}
          onChange={(event) => setStatus(event.target.value)}
          placeholder="—"
          options={['ISSUED', 'PAID', 'CANCELLED'].map((value) => ({ value, label: value }))}
        />
      </div>
      <ResourceView
        state={state}
        onRetry={reload}
        isEmpty={(rows) => rows.length === 0}
        emptyState={<p className={styles.muted}>{t('payments.empty')}</p>}
      >
        {(rows) => <DataTable caption={t('tab.payments')} columns={columns} rows={rows} rowKey={(row) => row.id} />}
      </ResourceView>
    </>
  );
}

function PlansTab() {
  const t = useTranslations('admin');
  const tErrors = useTranslations('errors');
  const toast = useToast();
  const { state, reload } = useApiResource<readonly PlanSummary[]>((signal) => api.admin.plans(signal), []);
  const [edits, setEdits] = useState<Record<string, { name?: string; priceCents?: number }>>({});

  const save = (plan: PlanSummary): void => {
    const edit = edits[plan.code] ?? {};
    api.admin
      .editPlan(plan.code, edit)
      .then(() => {
        toast.success(t('plans.saved'));
        reload();
      })
      .catch((caught: unknown) => toast.error(t('tab.plans'), tErrors(toApiError(caught).kind)));
  };

  return (
    <ResourceView state={state} onRetry={reload} isEmpty={() => false} emptyState={null}>
      {(plans) => (
        <div className={styles.form}>
          {plans.map((plan) => (
            <fieldset key={plan.code} className={styles.fieldset}>
              <legend>{plan.code}</legend>
              <Input
                label={t('plans.name')}
                defaultValue={plan.name}
                onChange={(event) =>
                  setEdits((current) => ({ ...current, [plan.code]: { ...current[plan.code], name: event.target.value } }))
                }
              />
              <Input
                label={t('plans.price')}
                type="number"
                defaultValue={plan.priceCents}
                onChange={(event) =>
                  setEdits((current) => ({
                    ...current,
                    [plan.code]: { ...current[plan.code], priceCents: Number(event.target.value) },
                  }))
                }
              />
              <Button variant="secondary" onClick={() => save(plan)}>
                {t('plans.save')}
              </Button>
            </fieldset>
          ))}
        </div>
      )}
    </ResourceView>
  );
}

type SettingsMap = Record<string, unknown>;
const field = (value: unknown): string => (value === undefined || value === null ? '' : String(value));

function SettingsTab() {
  const t = useTranslations('admin');
  const tErrors = useTranslations('errors');
  const toast = useToast();
  const { state, reload } = useApiResource<SettingsMap>((signal) => api.admin.settings(signal), []);

  const put = async (key: string, value: unknown): Promise<void> => {
    try {
      await api.admin.putSetting(key, value);
      toast.success(t('settings.saved'));
      reload();
    } catch (caught) {
      toast.error(t('tab.settings'), tErrors(toApiError(caught).kind));
    }
  };

  return (
    <ResourceView state={state} onRetry={reload} isEmpty={() => false} emptyState={null}>
      {(settings) => (
        <>
          <ObjectForm
            title={t('settings.payee')}
            settingKey="billing.payee"
            keys={['name', 'address', 'iban', 'bic', 'taxId', 'vatId', 'note']}
            labelPrefix="settings.payee."
            value={(settings['billing.payee'] ?? {}) as Record<string, unknown>}
            onSave={put}
            multiline={['address', 'note']}
          />
          <ObjectForm
            title={t('settings.crypto')}
            settingKey="billing.crypto"
            keys={['asset', 'network', 'address', 'eurPerUnit']}
            labelPrefix="settings.crypto."
            value={(settings['billing.crypto'] ?? {}) as Record<string, unknown>}
            onSave={put}
            multiline={[]}
          />
          <NumberSettings settings={settings} onSave={put} />
        </>
      )}
    </ResourceView>
  );
}

function ObjectForm({
  title,
  settingKey,
  keys,
  labelPrefix,
  value,
  onSave,
  multiline,
}: {
  readonly title: string;
  readonly settingKey: string;
  readonly keys: readonly string[];
  readonly labelPrefix: string;
  readonly value: Record<string, unknown>;
  readonly onSave: (key: string, value: unknown) => Promise<void>;
  readonly multiline: readonly string[];
}) {
  const t = useTranslations('admin');
  const [draft, setDraft] = useState<Record<string, string>>(() =>
    Object.fromEntries(keys.map((key) => [key, field(value[key])])),
  );
  const submit = (event: FormEvent): void => {
    event.preventDefault();
    void onSave(settingKey, draft);
  };
  return (
    <form className={styles.form} onSubmit={submit}>
      <h3>{title}</h3>
      {keys.map((key) => {
        const label = t(`${labelPrefix}${key}` as 'settings.payee.name');
        return multiline.includes(key) ? (
          <Textarea
            key={key}
            label={label}
            rows={3}
            value={draft[key] ?? ''}
            onChange={(event) => setDraft({ ...draft, [key]: event.target.value })}
          />
        ) : (
          <Input
            key={key}
            label={label}
            value={draft[key] ?? ''}
            onChange={(event) => setDraft({ ...draft, [key]: event.target.value })}
          />
        );
      })}
      <div>
        <Button type="submit" variant="primary">
          {t('settings.save')}
        </Button>
      </div>
    </form>
  );
}

function NumberSettings({
  settings,
  onSave,
}: {
  readonly settings: SettingsMap;
  readonly onSave: (key: string, value: unknown) => Promise<void>;
}) {
  const t = useTranslations('admin');
  const [draft, setDraft] = useState({
    trial: field(settings['billing.trial_days']),
    retention: field(settings['billing.retention_days']),
    vat: field(settings['billing.vat_percent']),
  });
  const submit = async (event: FormEvent): Promise<void> => {
    event.preventDefault();
    await onSave('billing.trial_days', Number(draft.trial));
    await onSave('billing.retention_days', Number(draft.retention));
    await onSave('billing.vat_percent', Number(draft.vat));
  };
  return (
    <form className={styles.form} onSubmit={(event) => void submit(event)}>
      <h3>{t('settings.billing')}</h3>
      <Input label={t('settings.trialDays')} type="number" value={draft.trial} onChange={(e) => setDraft({ ...draft, trial: e.target.value })} />
      <Input label={t('settings.retentionDays')} type="number" value={draft.retention} onChange={(e) => setDraft({ ...draft, retention: e.target.value })} />
      <Input label={t('settings.vatPercent')} type="number" value={draft.vat} onChange={(e) => setDraft({ ...draft, vat: e.target.value })} />
      <div>
        <Button type="submit" variant="primary">
          {t('settings.save')}
        </Button>
      </div>
    </form>
  );
}

function ServicesTab() {
  const t = useTranslations('admin');
  const { state, reload } = useApiResource<readonly AdminService[]>((signal) => api.admin.services(signal), []);
  return (
    <>
      <p className={styles.muted}>{t('services.hint')}</p>
      <ResourceView state={state} onRetry={reload} isEmpty={() => false} emptyState={null}>
        {(services) => (
          <div className={styles.form}>
            {services.map((service) => (
              <Card key={service.id}>
                <CardBody>
                  <strong>{service.name}</strong>{' '}
                  <StatusPill
                    label={service.enabled ? t('services.on') : t('services.off')}
                    status={service.enabled ? 'connected' : 'disconnected'}
                  />
                  <p className={styles.muted}>{service.detail}</p>
                  <p className={styles.mono}>{t('services.setting', { setting: service.setting })}</p>
                </CardBody>
              </Card>
            ))}
          </div>
        )}
      </ResourceView>
    </>
  );
}
