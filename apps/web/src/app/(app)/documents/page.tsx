'use client';

import { useState } from 'react';
import { ExportButton } from '@/components/app/export-button';
import { ListPage } from '@/components/app/list-page';
import { MergeDialog } from '@/components/app/merge-dialog';
import { StatusPill } from '@/components/ui';
import { Button } from '@/components/ui/button';
import { useToast } from '@/components/ui/toast';
import { toApiError } from '@/lib/api/errors';
import type { Column } from '@/components/ui';
import { formatDate } from '@/i18n/format';
import { useLocale, useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { useApiResource } from '@/lib/api/hooks';
import type { DocumentSummary } from '@/lib/api/types';
import { useSession } from '@/lib/auth/session-context';
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

export default function DocumentsPage() {
  const t = useTranslations('documents');
  const tCommon = useTranslations('common');
  const locale = useLocale();
  const statusLabel = useStatusLabels('documents');
  const [category, setCategory] = useState<(typeof CATEGORY_TABS)[number]>('ALL');
  const user = useSession();
  const toast = useToast();
  const [listVersion, setListVersion] = useState(0);
  const [sorting, setSorting] = useState(false);
  const [mergeOpen, setMergeOpen] = useState(false);
  const tErrors = useTranslations('errors');
  const parties = useApiResource((signal) => api.parties.list(signal), []);
  const catalogue = useApiResource((signal) => api.documents.catalogue(signal), []);
  const partyName = (id: string | null | undefined): string =>
    (parties.state.status === 'ready' ? parties.state.data.find((party) => party.id === id)?.displayName : null) ??
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
    ...(parties.state.status === 'ready' && parties.state.data.length > 1
      ? [{ key: 'party', header: t('column.party'), render: (row: DocumentSummary) => partyName(row.partyId) }]
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
      key: 'uploadedBy',
      header: t('column.uploadedBy'),
      render: (row) => row.uploadedByName ?? (row.channel === 'email' ? t('uploadedBy.mail') : t('uploadedBy.unknown')),
    },
    {
      key: 'status',
      header: t('column.status'),
      render: (row) => <StatusPill label={statusLabel(row.status)} status={row.status} />,
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
              onClick={() => setCategory(tab)}
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
        key={`${category}-${listVersion}`}
        screenId="documents"
        namespace="documents"
        load={(params, signal) =>
          api.documents.list(category === 'ALL' ? params : { ...params, category }, signal)
        }
        columns={columns}
        sortableKeys={['title', 'receivedAt']}
        filters={[
          ...(parties.state.status === 'ready' && parties.state.data.length > 1
            ? [
                {
                  key: 'party',
                  label: t('filter.party'),
                  options: [
                    ...parties.state.data.map((party) => ({ value: party.id, label: party.displayName })),
                    { value: 'NONE', label: t('party.none') },
                  ],
                },
              ]
            : []),
          ...(catalogue.state.status === 'ready'
            ? [
                {
                  key: 'type',
                  label: t('filter.type'),
                  options: catalogue.state.data
                    .filter((entry) => !user?.workspaceType || entry.audiences.split(',').includes(user.workspaceType))
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
            <div style={{ display: 'flex', alignItems: 'center', gap: '0.75rem', flexWrap: 'wrap' }}>
              <span>{t('bulk.count', { count: ids.length })}</span>
              <Button
                variant="danger"
                iconLeft="trash"
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
