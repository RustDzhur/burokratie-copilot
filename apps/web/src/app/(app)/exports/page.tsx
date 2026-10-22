'use client';

import { useEffect, useMemo, useState, type FormEvent } from 'react';
import { PageHeader, PageSection, ResourceView } from '@/components/app/resource-view';
import { Badge, Card, CardBody, CardHeader, DataTable, EmptyState, type Column } from '@/components/ui';
import { Button } from '@/components/ui/button';
import { InlineAlert, SkeletonTable } from '@/components/ui/feedback';
import { Input, Select } from '@/components/ui/form';
import { useToast } from '@/components/ui/toast';
import { formatCurrency, formatDate, formatDateTime, formatNumber } from '@/i18n/format';
import { useLocale, useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { toApiError } from '@/lib/api/errors';
import { useApiResource } from '@/lib/api/hooks';
import type {
  ContactSummary,
  DatevExportBatch,
  DatevExportResult,
  DatevSkippedDocument,
} from '@/lib/api/types';
import { downloadBlob } from '@/lib/download';
import styles from './exports.module.css';

const iso = (date: Date): string =>
  `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;

/** The current month is the period a bookkeeper most often exports; both ends stay editable. */
function monthStart(): string {
  const now = new Date();
  return iso(new Date(now.getFullYear(), now.getMonth(), 1));
}

function monthEnd(): string {
  const now = new Date();
  return iso(new Date(now.getFullYear(), now.getMonth() + 1, 0));
}

/**
 * DATEV export packages (Kanzlei edition, §5.6).
 *
 * <p><strong>The format is provisional.</strong> The warning is permanent and sits above the form, not in a
 * dismissible toast: the exact field profile is only fixed with the first Kanzlei in Appendix 1, and a screen
 * that hides that would invite somebody to feed an unagreed file into DATEV.</p>
 *
 * <p>The screen reports what the service decided: how many vouchers left, their total, and every voucher that
 * stayed behind with the reason. It never computes that itself - a second aggregation would be a second truth.
 * </p>
 */
export default function DatevExportsPage() {
  const t = useTranslations('exports');
  const tNav = useTranslations('nav');
  const tErrors = useTranslations('errors');
  const toast = useToast();
  const locale = useLocale();

  const [periodFrom, setPeriodFrom] = useState(monthStart);
  const [periodTo, setPeriodTo] = useState(monthEnd);
  const [mandantId, setMandantId] = useState('');
  const [mandants, setMandants] = useState<readonly ContactSummary[]>([]);
  const [creating, setCreating] = useState(false);
  const [downloading, setDownloading] = useState<string | null>(null);
  const [formError, setFormError] = useState<string | null>(null);
  const [result, setResult] = useState<DatevExportResult | null>(null);

  const history = useApiResource((signal) => api.exports.datev.list(signal), []);

  // The client list is optional: an export right does not have to include the right to read contacts, and a
  // failed lookup must not take the export screen down. The select simply offers "all clients" then.
  useEffect(() => {
    const controller = new AbortController();
    void api.contacts
      .list({ size: 250, sort: 'name,asc' }, controller.signal)
      .then((page) => setMandants(page.items))
      .catch(() => setMandants([]));
    return () => controller.abort();
  }, []);

  const explain = (reason: string): string => {
    switch (reason) {
      case 'Ohne Mandant':
        return t('result.reasonNoMandant');
      case 'Kein Konto-Mapping':
        return t('result.reasonNoMapping');
      default:
        return reason;
    }
  };

  const create = async (event: FormEvent<HTMLFormElement>): Promise<void> => {
    event.preventDefault();
    setFormError(null);
    if (!periodFrom || !periodTo) {
      setFormError(t('form.requiredPeriod'));
      return;
    }
    if (periodFrom > periodTo) {
      setFormError(t('form.invalidPeriod'));
      return;
    }
    setResult(null);
    setCreating(true);
    try {
      const created = await api.exports.datev.create({
        periodFrom,
        periodTo,
        mandantId: mandantId === '' ? null : mandantId,
      });
      setResult(created);
      toast.success(t('form.created', { count: created.belegCount }), created.filename);
      history.reload();
    } catch (error) {
      toast.error(tErrors(toApiError(error).kind));
    } finally {
      setCreating(false);
    }
  };

  const download = async (id: string, filename: string): Promise<void> => {
    setDownloading(id);
    try {
      downloadBlob(await api.exports.datev.download(id), filename);
    } catch (error) {
      toast.error(tErrors(toApiError(error).kind));
    } finally {
      setDownloading(null);
    }
  };

  const skippedColumns: readonly Column<DatevSkippedDocument>[] = useMemo(
    () => [
      {
        key: 'document',
        header: t('result.document'),
        render: (row) => (
          <span className={styles.documentId} title={row.documentId}>
            {row.documentId.slice(0, 8)}
          </span>
        ),
      },
      {
        key: 'reason',
        header: t('result.reason'),
        render: (row) => explain(row.reason),
      },
    ],
    // `explain` depends only on the catalogue; the deps below are the values it reads.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [t],
  );

  const historyColumns: readonly Column<DatevExportBatch>[] = useMemo(
    () => [
      {
        key: 'createdAt',
        header: t('history.createdAt'),
        render: (row) => formatDateTime(row.createdAt, locale),
      },
      {
        key: 'period',
        header: t('history.period'),
        render: (row) =>
          `${formatDate(row.periodFrom, locale)} – ${formatDate(row.periodTo, locale)}`,
      },
      {
        key: 'mandant',
        header: t('history.mandant'),
        render: (row) => row.mandantName ?? t('history.allMandants'),
      },
      {
        key: 'belege',
        header: t('history.belege'),
        align: 'end',
        render: (row) => formatNumber(row.belegCount, locale),
      },
      {
        key: 'amount',
        header: t('history.amount'),
        align: 'end',
        render: (row) => formatCurrency(row.totalAmount, locale),
      },
      {
        key: 'skipped',
        header: t('history.skippedCount'),
        align: 'end',
        render: (row) => formatNumber(row.skippedCount, locale),
      },
      {
        key: 'status',
        header: t('history.status'),
        render: (row) => (
          <Badge tone={row.status === 'FAILED' ? 'danger' : 'success'}>
            {row.status === 'FAILED' ? t('history.statusFailed') : t('history.statusCreated')}
          </Badge>
        ),
      },
      {
        key: 'file',
        header: t('history.file'),
        render: (row) => <span className={styles.filename}>{row.filename}</span>,
      },
    ],
    [t, locale],
  );

  return (
    <>
      <PageHeader title={tNav('items.exports')} subtitle={t('subtitle')} />

      <InlineAlert tone="warning" title={t('provisional.title')}>
        {t('provisional.text')}
      </InlineAlert>

      <PageSection title={t('form.title')} description={t('form.description')}>
        <Card>
          <CardBody>
            <form className={styles.form} onSubmit={(event) => void create(event)}>
              <Input
                id="datev-period-from"
                type="date"
                label={t('form.periodFrom')}
                value={periodFrom}
                onChange={(event) => setPeriodFrom(event.target.value)}
                required
              />
              <Input
                id="datev-period-to"
                type="date"
                label={t('form.periodTo')}
                value={periodTo}
                onChange={(event) => setPeriodTo(event.target.value)}
                required
              />
              <Select
                id="datev-mandant"
                label={t('form.mandant')}
                value={mandantId}
                onChange={(event) => setMandantId(event.target.value)}
                options={[
                  { value: '', label: t('form.allMandants') },
                  ...mandants.map((mandant) => ({ value: mandant.id, label: mandant.name })),
                ]}
              />
              <Button type="submit" iconLeft="download" loading={creating}>
                {t('form.submit')}
              </Button>
            </form>
            {formError ? (
              <InlineAlert tone="danger" className={styles.formError}>
                {formError}
              </InlineAlert>
            ) : null}
          </CardBody>
        </Card>
      </PageSection>

      {result ? (
        <PageSection title={t('result.title')}>
          <Card>
            <CardHeader
              title={t('result.title')}
              description={result.filename}
              icon="download"
              headingLevel={3}
            />
            <CardBody>
              <dl className={styles.stats}>
                <div className={styles.stat}>
                  <dt>{t('result.exported')}</dt>
                  <dd className={styles.statValue}>{formatNumber(result.belegCount, locale)}</dd>
                </div>
                <div className={styles.stat}>
                  <dt>{t('result.total')}</dt>
                  <dd className={styles.statValue}>
                    {formatCurrency(result.totalAmount, locale)}
                  </dd>
                </div>
                <div className={styles.stat}>
                  <dt>{t('result.skipped')}</dt>
                  <dd className={styles.statValue}>{formatNumber(result.skipped.length, locale)}</dd>
                </div>
              </dl>

              <div className={styles.resultActions}>
                <Button
                  iconLeft="download"
                  loading={downloading === result.id}
                  onClick={() => void download(result.id, result.filename)}
                >
                  {t('result.download')}
                </Button>
                <span className={styles.filename}>{result.filename}</span>
              </div>

              <h3 className={styles.subheading}>{t('result.skipped')}</h3>
              {result.skipped.length === 0 ? (
                <p className={styles.hint}>{t('result.skippedEmpty')}</p>
              ) : (
                <>
                  <p className={styles.hint}>{t('result.skippedCount', { count: result.skipped.length })}</p>
                  <DataTable
                    caption={t('result.skipped')}
                    columns={skippedColumns}
                    rows={result.skipped}
                    rowKey={(row) => row.documentId}
                    dense
                  />
                </>
              )}
            </CardBody>
          </Card>
        </PageSection>
      ) : null}

      <PageSection title={t('history.title')} description={t('history.description')}>
        <ResourceView
          state={history.state}
          onRetry={history.reload}
          isRefreshing={history.isRefreshing}
          isEmpty={(rows) => rows.length === 0}
          emptyState={
            <EmptyState
              icon="download"
              title={t('history.empty')}
              description={t('history.description')}
            />
          }
          skeleton={<SkeletonTable rows={4} columns={6} />}
        >
          {(rows) => (
            <DataTable
              caption={t('history.title')}
              columns={historyColumns}
              rows={rows}
              rowKey={(row) => row.id}
              emptyState={<p className={styles.hint}>{t('history.empty')}</p>}
              rowActions={(row) =>
                row.status === 'CREATED' ? (
                  <Button
                    variant="ghost"
                    size="sm"
                    iconLeft="download"
                    loading={downloading === row.id}
                    onClick={() => void download(row.id, row.filename)}
                  >
                    {t('history.download')}
                  </Button>
                ) : null
              }
            />
          )}
        </ResourceView>
      </PageSection>
    </>
  );
}
