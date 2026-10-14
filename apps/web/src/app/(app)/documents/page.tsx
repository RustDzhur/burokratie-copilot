'use client';

import { useState } from 'react';
import { ExportButton } from '@/components/app/export-button';
import { ListPage } from '@/components/app/list-page';
import { StatusPill } from '@/components/ui';
import type { Column } from '@/components/ui';
import { formatDate } from '@/i18n/format';
import { useLocale, useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { useApiResource } from '@/lib/api/hooks';
import type { DocumentSummary } from '@/lib/api/types';
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
  const { state: counts } = useApiResource((signal) => api.documents.categoryCounts(signal), []);
  const countOf = (tab: string): number | null => {
    if (counts.status !== 'ready') return null;
    if (tab === 'ALL') return Object.values(counts.data).reduce((sum, value) => sum + value, 0);
    return counts.data[tab] ?? 0;
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
      <div role="tablist" aria-label={t('category.label')} style={{ display: 'flex', flexWrap: 'wrap', gap: '0.5rem', marginBottom: '1rem' }}>
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
        key={category}
        screenId="documents"
        namespace="documents"
        load={(params, signal) =>
          api.documents.list(category === 'ALL' ? params : { ...params, category }, signal)
        }
        columns={columns}
        sortableKeys={['title', 'receivedAt']}
        filters={[
          {
            key: 'status',
            label: t('filter.status'),
            options: STATUS_VALUES.map((value) => ({ value, label: statusLabel(value) })),
          },
        ]}
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
