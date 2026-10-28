'use client';

import { useRouter } from 'next/navigation';
import { useCallback, useMemo, useRef, useState } from 'react';
import { ListPage } from '@/components/app/list-page';
import { Badge, StatusPill } from '@/components/ui/data';
import type { Column } from '@/components/ui';
import { Button } from '@/components/ui/button';
import { FileDropzone, UploadList, useUploadQueue } from '@/components/ui/file-dropzone';
import { InlineAlert } from '@/components/ui/feedback';
import { Dialog } from '@/components/ui/overlay';
import { useToast } from '@/components/ui/toast';
import { formatDate } from '@/i18n/format';
import { useEstimateText } from '@/i18n/use-estimate';
import { useLocale, useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { toApiError } from '@/lib/api/errors';
import type { DocumentSummary } from '@/lib/api/types';
import { useGroupedLabels, useStatusLabels } from '@/lib/format/status';
import styles from './inbox.module.css';

const STATUS_VALUES = [
  'received',
  'processing',
  'needs_review',
  'processed',
  'failed',
  'archived',
] as const;
const CHANNEL_VALUES = ['upload', 'email', 'scan', 'api', 'mobile'] as const;

/**
 * Document inbox (§5.2.5): every channel lands here.
 *
 * Uploads run through the shared queue with real progress and cancellation; the
 * list refreshes only after the backend confirms the upload.
 */
export default function InboxPage() {
  const t = useTranslations('inbox');
  const tCommon = useTranslations('common');
  const tErrors = useTranslations('errors');
  const tStates = useTranslations('states');
  const locale = useLocale();
  const estimate = useEstimateText();
  const toast = useToast();
  const statusLabel = useStatusLabels('inbox');
  const channelLabel = useGroupedLabels('inbox', 'channel');

  const [uploadOpen, setUploadOpen] = useState(false);
  const [reloadToken, setReloadToken] = useState(0);
  const router = useRouter();
  // A person who uploads one document wants to see what happens to it, so they are taken to it; several
  // files stay in the list, where each one has its own status.
  const lastUploadedId = useRef<string | null>(null);
  const queueSize = useRef(0);

  const queue = useUploadQueue({
    upload: async (file, context) => {
      const uploaded = await api.documents.upload(file, {
        onProgress: context.onProgress,
        signal: context.signal,
        idempotencyKey: true,
      });
      lastUploadedId.current = uploaded.id;
    },
    onUploaded: () => {
      toast.success(tStates('success.created'), t('action.upload'));
      setReloadToken((value) => value + 1);
      if (queueSize.current <= 1 && lastUploadedId.current) {
        setUploadOpen(false);
        router.push(`/documents/${lastUploadedId.current}`);
      }
    },
    onFailed: (file, message) => {
      toast.error(tErrors('network'), `${file.name}: ${message}`);
    },
  });

  queueSize.current = queue.items.length;

  const columns = useMemo<readonly Column<DocumentSummary>[]>(
    () => [
      {
        key: 'document',
        header: t('column.document'),
        link: (row) => `/documents/${row.id}`,
        render: (row) => row.title,
      },
      {
        key: 'sender',
        header: t('column.sender'),
        render: (row) => row.senderName ?? tCommon('labels.none'),
      },
      {
        key: 'receivedAt',
        header: t('column.receivedAt'),
        sortable: true,
        render: (row) => formatDate(row.receivedAt, locale, 'medium'),
      },
      { key: 'channel', header: t('column.channel'), render: (row) => channelLabel(row.channel) },
      {
        key: 'status',
        header: t('column.status'),
        render: (row) => <StatusPill label={statusLabel(row.status)} status={row.status} />,
      },
      {
        key: 'confidence',
        header: t('column.confidence'),
        align: 'end',
        render: (row) =>
          typeof row.confidence === 'number' ? (
            <Badge
              tone={
                row.confidence >= 0.85 ? 'success' : row.confidence >= 0.6 ? 'warning' : 'danger'
              }
            >
              {estimate(row.confidence)}
            </Badge>
          ) : (
            tCommon('labels.unknown')
          ),
      },
    ],
    [channelLabel, estimate, locale, statusLabel, t, tCommon],
  );

  const archive = useCallback(
    async (row: DocumentSummary) => {
      try {
        await api.documents.archive(row.id);
        toast.success(tStates('success.saved'), row.title);
        setReloadToken((value) => value + 1);
      } catch (error) {
        toast.error(tErrors(toApiError(error).kind));
      }
    },
    [tErrors, tStates, toast],
  );

  return (
    <div key={reloadToken}>
      <ListPage<DocumentSummary>
        screenId="inbox"
        namespace="inbox"
        deletable={{ kind: 'DOCUMENT', rowLabel: (row) => row.title }}
        load={(params, signal) => api.documents.list(params, signal)}
        columns={columns}
        sortableKeys={['receivedAt']}
        filters={[
          {
            key: 'status',
            label: t('filter.status'),
            options: STATUS_VALUES.map((value) => ({ value, label: statusLabel(value) })),
          },
          {
            key: 'channel',
            label: t('filter.channel'),
            options: CHANNEL_VALUES.map((value) => ({ value, label: channelLabel(value) })),
          },
        ]}
        headerActions={
          <>
            <Button variant="secondary" iconLeft="printer" onClick={() => setUploadOpen(true)}>
              {t('action.scan')}
            </Button>
            <Button variant="primary" iconLeft="upload" onClick={() => setUploadOpen(true)}>
              {t('action.upload')}
            </Button>
          </>
        }
        rowActions={(row) => (
          <Button variant="ghost" size="sm" onClick={() => void archive(row)}>
            {t('action.archive')}
          </Button>
        )}
      />

      <Dialog
        open={uploadOpen}
        onClose={() => setUploadOpen(false)}
        title={t('action.upload')}
        description={t('subtitle')}
      >
        <div className={styles.uploadDialog}>
          <FileDropzone onSelect={(files) => queue.enqueue(files)} />
          <UploadList
            items={queue.items}
            onCancel={queue.cancel}
            onRetry={queue.retry}
            onRemove={queue.remove}
            title={t('column.document')}
          />
          <InlineAlert tone="info">{tCommon('labels.hint')}</InlineAlert>
        </div>
      </Dialog>
    </div>
  );
}
