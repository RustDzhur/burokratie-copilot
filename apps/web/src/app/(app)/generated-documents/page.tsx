'use client';

import { ListPage } from '@/components/app/list-page';
import { Badge } from '@/components/ui';
import type { Column } from '@/components/ui';
import { buttonClass } from '@/components/ui/button-class';
import { formatDate } from '@/i18n/format';
import { useLocale, useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import type { GeneratedDocumentSummary } from '@/lib/api/types';

/**
 * The letters this account generated (spec §15).
 *
 * <p><strong>Why this page exists.</strong> Letters could be generated and were shown nowhere: no list route
 * and no screen, so the readiness check and the download existed as routes with nothing to click them from.</p>
 *
 * <p>The download is offered only where a file exists. The readiness check belongs here too, but it needs a
 * page of its own to show what stands in the way; until that exists the link is not offered, because a link
 * that lands on nothing is worse than no link - and this page had exactly that for a few minutes.</p>
 */
export default function GeneratedDocumentsPage() {
  const t = useTranslations('generatedDocuments');
  const locale = useLocale();

  const columns: readonly Column<GeneratedDocumentSummary>[] = [
    { key: 'title', header: t('column.title'), render: (row) => row.title },
    {
      key: 'status',
      header: t('column.status'),
      render: (row) => <Badge tone={row.hasFile ? 'success' : 'neutral'}>{row.status}</Badge>,
    },
    { key: 'format', header: t('column.format'), render: (row) => row.outputFormat },
    {
      key: 'generatedAt',
      header: t('column.generatedAt'),
      render: (row) => (row.generatedAt ? formatDate(row.generatedAt, locale) : '—'),
    },
  ];

  return (
    <ListPage<GeneratedDocumentSummary>
      screenId="generated-documents"
      namespace="generatedDocuments"
      load={(params, signal) => api.generatedDocuments.list(params, signal)}
      columns={columns}
      rowActions={(row) => (
        <>
          {row.hasFile ? (
            <a href={api.generatedDocuments.downloadUrl(row.id)} target="_blank" rel="noopener"
               className={buttonClass({ variant: 'secondary', size: 'sm' })}>
              {t('action.download')}
            </a>
          ) : null}
        </>
      )}
    />
  );
}
