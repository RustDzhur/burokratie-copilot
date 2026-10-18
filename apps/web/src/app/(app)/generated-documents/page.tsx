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
 * and no screen, so a letter produced a minute ago could not be found again unless its id was known. The
 * readiness check and the download existed as routes for the same reason — there was nothing to click them
 * from.</p>
 *
 * <p>The download is offered only where a file exists, and the readiness check is a question a person asks
 * before sending: a letter with open placeholders is exactly the one that must not go out.</p>
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
          <a href={`/generated-documents/${row.id}`}
             className={buttonClass({ variant: 'ghost', size: 'sm' })}>
            {t('action.readiness')}
          </a>
        </>
      )}
    />
  );
}
