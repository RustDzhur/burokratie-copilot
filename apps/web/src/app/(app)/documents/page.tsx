'use client';

import { useEffect, useMemo, useState } from 'react';
import { BatchMandantDialog } from '@/components/app/batch-mandant-dialog';
import { DeadlineBadge } from '@/components/app/deadline-badge';
import { ExportButton } from '@/components/app/export-button';
import { ListPage } from '@/components/app/list-page';
import { MergeDialog } from '@/components/app/merge-dialog';
import { Badge, StatusPill } from '@/components/ui';
import { Button } from '@/components/ui/button';
import { useToast } from '@/components/ui/toast';
import { toApiError } from '@/lib/api/errors';
import type { Column } from '@/components/ui';
import { formatCurrency, formatDate } from '@/i18n/format';
import { useLocale, useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { useApiResource } from '@/lib/api/hooks';
import type {
  BelegBatchAction,
  BelegBatchResult,
  BelegStatusValue,
  DocumentSummary,
} from '@/lib/api/types';
import { useStatusLabels } from '@/lib/format/status';

/** The drawers documents are sorted into, in the order a person looks for them. */
const CATEGORY_TABS = [
  'ALL',
  'UNSORTED',
  'FINANCE',
  'CONTRACTS',
  'GOVERNMENT',
  'HOUSING',
  'EMPLOYMENT',
  'FAMILY_EDUCATION_HEALTH',
  'BUSINESS',
  'LEGAL',
  'OTHER',
] as const;

const STATUS_VALUES = [
  'received',
  'processing',
  'needs_review',
  'processed',
  'failed',
  'archived',
] as const;

/** The five states of the queue, in the order the office works through them (§8.1). */
const BELEG_STATUS_VALUES = ['NEU', 'OFFEN', 'ZU_PRUEFEN', 'EXPORTIERT', 'FEHLER'] as const;

/**
 * Writes the queue's state into the address bar so the link can be handed to a colleague.
 *
 * <p>{@code replaceState} instead of pushing: a filter is not a page visit, and a back button that walks
 * through every dropdown change would make the browser history useless. Unknown parameters are kept — the
 * page shares its address with features it does not own.</p>
 */
function writeShareableUrl(next: Readonly<Record<string, string>>): void {
  if (typeof window === 'undefined') {
    return;
  }
  const params = new URLSearchParams(window.location.search);
  for (const [key, value] of Object.entries(next)) {
    if (value === '' || value === 'ALL') {
      params.delete(key);
    } else {
      params.set(key, value);
    }
  }
  const query = params.toString();
  window.history.replaceState(null, '', `${window.location.pathname}${query === '' ? '' : `?${query}`}`);
}

export default function DocumentsPage() {
  const t = useTranslations('documents');
  const tCommon = useTranslations('common');
  const locale = useLocale();
  const statusLabel = useStatusLabels('documents');
  const [category, setCategory] = useState<(typeof CATEGORY_TABS)[number]>('ALL');
  const toast = useToast();
  const [listVersion, setListVersion] = useState(0);
  const [sorting, setSorting] = useState(false);
  const [mergeOpen, setMergeOpen] = useState(false);
  const [batchBusy, setBatchBusy] = useState(false);
  const [assigning, setAssigning] = useState<null | {
    readonly ids: readonly string[];
    readonly clear: () => void;
    readonly reload: () => void;
  }>(null);
  const tErrors = useTranslations('errors');
  // §7: the person a voucher belongs to is a Mandant, and §5.2 puts that client in mandant_id. Reading the
  // merged-away parties table would show names the export does not recognise.
  const mandanten = useApiResource((signal) => api.contacts.list({ size: 500 }, signal), []);
  // A link from the client card (§5.5) or a shared queue link hands the filters over (`?mandantId=…`,
  // `?belegStatus=…`, `?category=…`). Read after mount, not during render, so the page renders the same on
  // the server and in the browser; the list remounts once the values are known.
  const [deepLink, setDeepLink] = useState<{ belegStatus: string; mandantId: string } | null>(null);
  const [urlFilters, setUrlFilters] = useState<Record<string, string>>({});
  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const status = params.get('belegStatus') ?? '';
    const mandantId = params.get('mandantId') ?? '';
    const tab = params.get('category') ?? '';
    const validStatus = (BELEG_STATUS_VALUES as readonly string[]).includes(status) ? status : '';
    const validTab = (CATEGORY_TABS as readonly string[]).includes(tab)
      ? (tab as (typeof CATEGORY_TABS)[number])
      : 'ALL';
    if (validStatus !== '' || mandantId !== '') {
      setDeepLink({ belegStatus: validStatus, mandantId });
      setUrlFilters({
        ...(validStatus === '' ? {} : { belegStatus: validStatus }),
        ...(mandantId === '' ? {} : { mandantId }),
      });
    }
    if (validTab !== 'ALL') {
      setCategory(validTab);
    }
  }, []);
  const catalogue = useApiResource((signal) => api.documents.catalogue(signal), []);
  const mandantName = (id: string | null | undefined): string =>
    (mandanten.state.status === 'ready'
      ? mandanten.state.data.items.find((contact) => contact.id === id)?.name
      : null) ??
    '—';
  const { state: counts, reload: reloadCounts } = useApiResource((signal) => api.documents.categoryCounts(signal), []);
  // §5.1: the six numbers a bookkeeper reads before anything else, in one request.
  const { state: queue, reload: reloadQueue } = useApiResource(
    (signal) => api.documents.belegCounts(signal),
    [],
  );
  const countOf = (tab: string): number | null => {
    if (counts.status !== 'ready') return null;
    if (tab === 'ALL') return Object.values(counts.data).reduce((sum, value) => sum + value, 0);
    return counts.data[tab] ?? 0;
  };
  // The open tab is sent with every request but is not a filter widget: adding it here refetches without
  // remounting the list, so the filters the person set survive switching the tab.
  const extraParams = useMemo(() => (category === 'ALL' ? undefined : { category }), [category]);
  const initialFilters = deepLink === null
    ? undefined
    : {
        ...(deepLink.belegStatus === '' ? {} : { belegStatus: deepLink.belegStatus }),
        ...(deepLink.mandantId === '' ? {} : { mandantId: deepLink.mandantId }),
      };

  const resort = async (): Promise<void> => {
    if (!window.confirm(t('resort.confirm'))) return;
    setSorting(true);
    try {
      const result = await api.documents.resort();
      toast.success(t('resort.done', result));
      reloadCounts();
      setListVersion((value) => value + 1);
    } catch (error) {
      toast.error(tErrors(toApiError(error).kind));
    } finally {
      setSorting(false);
    }
  };

  const deleteMany = async (ids: readonly string[]): Promise<boolean> => {
    if (!window.confirm(ids.length === 1 ? t('row.confirm') : t('bulk.confirm', { count: ids.length }))) {
      return false;
    }
    try {
      const result = await api.documents.removeMany(ids);
      if (result.refused.length === 0) {
        toast.success(ids.length === 1 ? t('row.done') : t('bulk.done', { count: result.deleted }));
      } else {
        toast.warning(t('bulk.partial', { count: result.deleted, refused: result.refused.length }));
      }
      reloadCounts();
      return true;
    } catch (error) {
      toast.error(tErrors(toApiError(error).kind));
      return false;
    }
  };

  const [moving, setMoving] = useState<string | null>(null);
  // Moving a voucher is one click and one request; a failure must say so instead of leaving the row unchanged
  // and unexplained.
  const moveBeleg = async (id: string, status: BelegStatusValue) => {
    setMoving(id);
    try {
      await api.documents.setBelegStatus(id, status);
      toast.success(t('beleg.moved'));
      setListVersion((value) => value + 1);
      reloadQueue();
    }
    catch (error) {
      toast.error(toApiError(error).message);
    }
    finally {
      setMoving(null);
    }
  };

  /**
   * Announces a bulk result the way the office reads it: how many vouchers moved, and the reasons the rest
   * stayed. A toast that only said "done" would hide exactly the vouchers the person has to look at.
   */
  const announceBatch = (result: BelegBatchResult, total: number): void => {
    // Identical reasons are one line: ten vouchers refused for the same rule are one thing to understand.
    const reasons = [...new Set(result.skipped.map((entry) => entry.reason))];
    if (reasons.length === 0) {
      toast.success(t('bulk.batchDone', { count: result.updated }));
      return;
    }
    toast.show({
      title: t('bulk.batchPartial', { updated: result.updated, total }),
      description: reasons.join(' · '),
      tone: 'warning',
      // A refused voucher is a message to read, not a notification to miss.
      durationMs: 8000,
    });
  };

  const runBatch = async (
    action: BelegBatchAction,
    ids: readonly string[],
    context: { readonly clear: () => void; readonly reload: () => void },
    mandantId?: string,
  ): Promise<void> => {
    setBatchBusy(true);
    try {
      const result = await api.documents.batch(ids, action, mandantId);
      announceBatch(result, ids.length);
      context.clear();
      context.reload();
      // §5.1: the counters are the first thing read after a bulk action, so they must not lag behind it.
      reloadQueue();
      reloadCounts();
    } catch (error) {
      toast.error(tErrors(toApiError(error).kind));
    } finally {
      setBatchBusy(false);
    }
  };

  const assignSelected = async (mandantId: string): Promise<void> => {
    if (assigning === null) {
      return;
    }
    const context = assigning;
    await runBatch('ASSIGN_MANDANT', context.ids, context, mandantId);
    setAssigning(null);
  };

  const changeCategory = (tab: (typeof CATEGORY_TABS)[number]): void => {
    setCategory(tab);
    writeShareableUrl({
      category: tab,
      belegStatus: urlFilters.belegStatus ?? '',
      mandantId: urlFilters.mandantId ?? '',
    });
  };

  const columns: readonly Column<DocumentSummary>[] = [
    {
      key: 'title',
      header: t('column.title'),
      sortable: true,
      link: (row) => `/documents/${row.id}`,
      render: (row) => row.title,
    },
    {
      key: 'type',
      header: t('column.type'),
      render: (row) =>
        row.typeNames?.[locale] ?? row.typeNames?.de ?? (row.documentType || t('category.UNSORTED')),
    },
    ...(mandanten.state.status === 'ready' && mandanten.state.data.items.length > 1
      ? [{ key: 'mandant', header: t('column.mandant'), render: (row: DocumentSummary) => (row.mandantId ? mandantName(row.mandantId) : t('beleg.ohneMandant')) }]
      : []),
    {
      key: 'sender',
      header: t('column.sender'),
      render: (row) =>
        row.senderName ? (
          <span title={row.sender?.address ?? undefined}>{row.senderName}</span>
        ) : (
          t('sender.unknown')
        ),
    },
    {
      key: 'supplier',
      header: t('column.supplier'),
      // §5.1: the supplier the extraction read. The sender of the letter is a different statement, shown
      // in its own column; an invoice without a supplier stays a dash here rather than borrowing one.
      render: (row) => row.supplierName ?? '—',
    },
    {
      key: 'amount',
      header: t('column.amount'),
      align: 'end',
      // §5.1: what the extraction found, written the way the office writes it. A voucher without an
      // amount shows a dash; the queue does not pretend a value exists.
      render: (row) =>
        row.amount === null || row.amount === undefined
          ? '—'
          : formatCurrency(row.amount, locale, row.amountCurrency ?? 'EUR'),
    },
    {
      key: 'documentDate',
      header: t('column.documentDate'),
      render: (row) => (row.documentDate ? formatDate(row.documentDate, locale) : '—'),
    },
    {
      key: 'uploadedBy',
      header: t('column.uploadedBy'),
      render: (row) => row.uploadedByName ?? (row.channel === 'email' ? t('uploadedBy.mail') : t('uploadedBy.unknown')),
    },
    {
      key: 'status',
      header: t('column.status'),
      render: (row) => (
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: '0.5rem', flexWrap: 'wrap' }}>
          <StatusPill label={statusLabel(row.status)} status={row.status} />
          {/* §5.1: the deadline stands next to the status, where the office looks first. No deadline,
              no badge — there is nothing to be urgent about. */}
          <DeadlineBadge date={row.deadlineDate} daysLeft={row.deadlineDaysLeft} />
        </span>
      ),
    },
    {
      key: 'belegStatus',
      header: t('column.belegStatus'),
      render: (row) => {
        const status = row.belegStatus ?? null;
        if (!status) {
          return '—';
        }
        // §5.1: the voucher's place in the office's work, with the one action that moves it forward.
        const next: BelegStatusValue | null =
          status === 'NEU' ? 'OFFEN' : status === 'OFFEN' ? 'ZU_PRUEFEN' : null;
        return (
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: '0.5rem' }}>
            {row.mandantId ? null : (
              // §5.2: an unrouted voucher is visible, not hidden behind a blank cell.
              <Badge tone="warning" variant="outline">
                {t('beleg.ohneMandant')}
              </Badge>
            )}
            <Badge
              tone={
                status === 'FEHLER'
                  ? 'danger'
                  : status === 'ZU_PRUEFEN'
                    ? 'warning'
                    : status === 'EXPORTIERT'
                      ? 'success'
                      : 'neutral'
              }
            >
              {t(`beleg.${status}` as 'beleg.NEU')}
            </Badge>
            {next ? (
              <Button
                variant="ghost"
                disabled={moving === row.id}
                onClick={() => void moveBeleg(row.id, next)}
              >
                {t(`beleg.next.${status}` as 'beleg.next.NEU')}
              </Button>
            ) : null}
          </span>
        );
      },
    },
    {
      key: 'receivedAt',
      header: t('column.createdAt'),
      sortable: true,
      render: (row) => formatDate(row.receivedAt, locale),
    },
    {
      key: 'retentionUntil',
      header: t('column.retentionUntil'),
      render: (row) =>
        row.retentionUntil ? formatDate(row.retentionUntil, locale) : tCommon('labels.unknown'),
    },
  ];

  return (
    <>
      <div style={{ marginBottom: '1.25rem' }}>
        <Button variant="secondary" loading={sorting} onClick={() => void resort()}>
          {t('resort.action')}
        </Button>{' '}
        <Button variant="secondary" onClick={() => setMergeOpen(true)}>
          {t('merge.action')}
        </Button>
      </div>
      <MergeDialog
        open={mergeOpen}
        onClose={() => setMergeOpen(false)}
        onMerged={() => {
          reloadCounts();
          setListVersion((value) => value + 1);
          reloadQueue();
        }}
      />
      <BatchMandantDialog
        open={assigning !== null}
        contacts={mandanten.state.status === 'ready' ? mandanten.state.data.items : []}
        count={assigning?.ids.length ?? 0}
        busy={batchBusy}
        onClose={() => setAssigning(null)}
        onConfirm={(mandantId) => void assignSelected(mandantId)}
      />
      {queue.status === 'ready' ? (
        <dl
          aria-label={t('queue.label')}
          style={{
            display: 'flex',
            flexWrap: 'wrap',
            gap: '1.5rem',
            margin: '0 0 1.25rem',
            padding: '0.75rem 1rem',
            border: '1px solid var(--color-border, #ddd)',
            borderRadius: '0.5rem',
          }}
        >
          {[
            { key: 'neu', value: queue.data.neu },
            { key: 'offen', value: queue.data.offen },
            { key: 'zuPruefen', value: queue.data.zuPruefen },
            { key: 'ohneMandant', value: queue.data.ohneMandant },
            { key: 'exportiert', value: queue.data.exportiert },
            { key: 'fehler', value: queue.data.fehler },
          ].map((entry) => (
            <div key={entry.key}>
              <dt style={{ fontSize: '0.8125rem', color: 'var(--color-text-muted, #666)' }}>
                {t(`queue.${entry.key}` as 'queue.neu')}
              </dt>
              <dd style={{ margin: 0, fontSize: '1.25rem', fontWeight: 600 }}>
                {/* Null means "not known yet" and must not be shown as zero, which would claim a check that
                    has not happened. */}
                {entry.value === null ? '—' : entry.value}
              </dd>
            </div>
          ))}
        </dl>
      ) : null}
      <div role="tablist" aria-label={t('category.label')} style={{ display: 'flex', flexWrap: 'wrap', gap: '0.75rem', marginBottom: '1.5rem' }}>
        {CATEGORY_TABS.map((tab) => {
          const count = countOf(tab);
          const active = tab === category;
          return (
            <button
              key={tab}
              type="button"
              role="tab"
              aria-selected={active}
              onClick={() => changeCategory(tab)}
              style={{
                padding: '0.4rem 0.8rem',
                borderRadius: '999px',
                border: '1px solid var(--color-border, #ccc)',
                background: active ? 'var(--color-accent, #2563eb)' : 'transparent',
                color: active ? 'var(--color-text-on-accent, #fff)' : 'inherit',
                cursor: 'pointer',
                font: 'inherit',
              }}
            >
              {t(`category.${tab}` as 'category.ALL')}
              {count === null ? '' : ` (${count})`}
            </button>
          );
        })}
      </div>
      <ListPage<DocumentSummary>
        key={`${listVersion}-${deepLink?.belegStatus ?? ''}-${deepLink?.mandantId ?? ''}`}
        screenId="documents"
        namespace="documents"
        initialFilters={initialFilters}
        extraParams={extraParams}
        load={(params, signal) => api.documents.list(params, signal)}
        columns={columns}
        sortableKeys={['title', 'receivedAt']}
        onFiltersChange={(filters) => {
          setUrlFilters(filters);
          // The address carries the queue filters, so a colleague who opens the link sees the same list.
          writeShareableUrl({
            category,
            belegStatus: filters.belegStatus ?? '',
            mandantId: filters.mandantId ?? '',
          });
        }}
        filters={[
          {
            key: 'mandantId',
            label: t('filter.mandant'),
            // Always offered: "Ohne Mandant" is the filter the office needs most, even before the client
            // list has loaded. The clients join the same select as soon as they are there.
            options: [
              ...(mandanten.state.status === 'ready'
                ? mandanten.state.data.items.map((contact) => ({ value: contact.id, label: contact.name }))
                : []),
              { value: 'NONE', label: t('beleg.ohneMandant') },
            ],
          },
          {
            key: 'belegStatus',
            label: t('filter.belegStatus'),
            options: BELEG_STATUS_VALUES.map((value) => ({
              value,
              label: t(`beleg.${value}` as 'beleg.NEU'),
            })),
          },
          ...(catalogue.state.status === 'ready'
            ? [
                {
                  key: 'type',
                  label: t('filter.type'),
                  options: catalogue.state.data
                    .map((entry) => ({
                      value: entry.code,
                      label: `${t(`category.${entry.category}` as 'category.ALL')} · ${entry.names[locale] ?? entry.names.de}`,
                    })),
                },
              ]
            : []),
          {
            key: 'status',
            label: t('filter.status'),
            options: STATUS_VALUES.map((value) => ({ value, label: statusLabel(value) })),
          },
        ]}
        selectable={{
          allLabel: t('bulk.selectAll'),
          rowLabel: (row) => t('bulk.selectRow', { name: row.title }),
          actions: ({ ids, clear, reload }) => (
            <div
              role="toolbar"
              aria-label={t('bulk.toolbar')}
              style={{ display: 'flex', alignItems: 'center', gap: '0.75rem', flexWrap: 'wrap' }}
            >
              <span>{t('bulk.count', { count: ids.length })}</span>
              <Button
                variant="secondary"
                disabled={batchBusy}
                onClick={() => void runBatch('ACCEPT', ids, { clear, reload })}
              >
                {t('bulk.accept')}
              </Button>
              <Button
                variant="secondary"
                disabled={batchBusy}
                onClick={() => void runBatch('TO_CHECK', ids, { clear, reload })}
              >
                {t('bulk.toCheck')}
              </Button>
              <Button
                variant="secondary"
                disabled={batchBusy}
                onClick={() => setAssigning({ ids, clear, reload })}
              >
                {t('bulk.assignMandant')}
              </Button>
              <Button
                variant="danger"
                disabled={batchBusy}
                onClick={() => void runBatch('MARK_ERROR', ids, { clear, reload })}
              >
                {t('bulk.markError')}
              </Button>
              <Button
                variant="danger"
                iconLeft="trash"
                disabled={batchBusy}
                onClick={() =>
                  void deleteMany(ids).then((done) => {
                    if (done) {
                      clear();
                      reload();
                    }
                  })
                }
              >
                {t('bulk.delete')}
              </Button>
              <Button variant="ghost" onClick={clear}>
                {t('bulk.clear')}
              </Button>
            </div>
          ),
        }}
        rowActions={(row, { reload }) => (
          <Button
            variant="ghost"
            iconLeft="trash"
            aria-label={`${t('row.delete')}: ${row.title}`}
            onClick={() => void deleteMany([row.id]).then((done) => done && reload())}
          >
            {t('row.delete')}
          </Button>
        )}
        headerActions={
          <ExportButton
            label={t('action.export')}
            filename="documents.csv"
            onExport={() => api.documents.export()}
          />
        }
      />
    </>
  );
}
