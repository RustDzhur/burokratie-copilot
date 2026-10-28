'use client';

import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ChangeEvent,
  type DragEvent,
} from 'react';
import { Icon } from '@/components/icons';
import { formatFileSize } from '@/i18n/format';
import { useLocale, useTranslations } from '@/i18n/provider';
import { Button, IconButton } from './button';
import { ProgressBar } from './feedback';
import styles from './file-dropzone.module.css';

export type UploadStatus = 'queued' | 'uploading' | 'done' | 'error' | 'cancelled' | 'scanning';

export interface UploadItem {
  readonly id: string;
  readonly name: string;
  readonly size: number;
  readonly progress: number;
  readonly status: UploadStatus;
  readonly error?: string;
}

export interface FileDropzoneProps {
  /** Called with accepted files (already validated against type and size). */
  readonly onSelect: (files: readonly File[]) => void;
  readonly accept?: readonly string[];
  readonly maxSizeMb?: number;
  readonly multiple?: boolean;
  readonly disabled?: boolean;
  readonly label?: string;
  readonly hint?: string;
  readonly className?: string;
}

const DEFAULT_ACCEPT = [
  'pdf',
  'jpg',
  'jpeg',
  'png',
  'tiff',
  'docx',
  'xlsx',
  'csv',
  'txt',
  'eml',
  'xml',
];

function extensionOf(name: string): string {
  const index = name.lastIndexOf('.');
  return index === -1 ? '' : name.slice(index + 1).toLowerCase();
}

/**
 * Drag & drop file selection with keyboard support.
 *
 * Validation (file type, size limits) happens before anything is sent, because
 * every file must be checked before OCR/AI processing (docs/task.md §21.2).
 * Rejections are announced politely instead of failing silently.
 */
export function FileDropzone({
  onSelect,
  accept = DEFAULT_ACCEPT,
  maxSizeMb = 50,
  multiple = true,
  disabled = false,
  label,
  hint,
  className,
}: FileDropzoneProps) {
  const t = useTranslations('a11y');
  const tCommon = useTranslations('common');
  const tScan = useTranslations('scan');
  const inputRef = useRef<HTMLInputElement | null>(null);
  const [dragging, setDragging] = useState(false);
  const [rejections, setRejections] = useState<readonly string[]>([]);

  const validate = useCallback(
    (files: readonly File[]): File[] => {
      const problems: string[] = [];
      const accepted: File[] = [];
      for (const file of files) {
        if (!accept.includes(extensionOf(file.name))) {
          problems.push(tScan('dropzone.invalidType', { name: file.name }));
          continue;
        }
        if (file.size > maxSizeMb * 1024 * 1024) {
          problems.push(tScan('dropzone.tooLarge', { name: file.name }));
          continue;
        }
        accepted.push(file);
      }
      setRejections(problems);
      return accepted;
    },
    [accept, maxSizeMb, tScan],
  );

  const handleFiles = useCallback(
    (files: readonly File[]) => {
      const accepted = validate(files);
      if (accepted.length > 0) onSelect(multiple ? accepted : accepted.slice(0, 1));
    },
    [multiple, onSelect, validate],
  );

  const onDrop = (event: DragEvent<HTMLDivElement>): void => {
    event.preventDefault();
    setDragging(false);
    if (disabled) return;
    handleFiles(Array.from(event.dataTransfer.files));
  };

  const onInputChange = (event: ChangeEvent<HTMLInputElement>): void => {
    handleFiles(Array.from(event.target.files ?? []));
    event.target.value = '';
  };

  return (
    <div className={[styles.wrapper, className].filter(Boolean).join(' ')}>
      <div
        className={[
          styles.zone,
          dragging ? styles.zoneDragging : '',
          disabled ? styles.zoneDisabled : '',
        ]
          .filter(Boolean)
          .join(' ')}
        onDragOver={(event) => {
          event.preventDefault();
          if (!disabled) setDragging(true);
        }}
        onDragLeave={() => setDragging(false)}
        onDrop={onDrop}
      >
        <span className={styles.icon} aria-hidden="true">
          <Icon name="upload" size={22} />
        </span>
        <p className={styles.title}>{label ?? t('upload.dropHint')}</p>
        <p className={styles.meta}>
          {t('upload.accepted', { formats: accept.join(', ') })} ·{' '}
          {t('upload.maxSize', { size: `${maxSizeMb} MB` })}
        </p>
        <Button
          variant="secondary"
          size="sm"
          onClick={() => inputRef.current?.click()}
          disabled={disabled}
        >
          {t('upload.browse')}
        </Button>
        <input
          ref={inputRef}
          type="file"
          className={styles.input}
          multiple={multiple}
          disabled={disabled}
          accept={accept.map((extension) => `.${extension}`).join(',')}
          onChange={onInputChange}
          aria-label={t('upload.browse')}
        />
        {hint ? <p className={styles.hint}>{hint}</p> : null}
      </div>
      <div aria-live="polite" className={styles.rejections}>
        {rejections.map((message) => (
          <p key={message} className={styles.rejection}>
            <Icon name="alert-circle" size={14} />
            <span>{message}</span>
          </p>
        ))}
        {rejections.length > 0 ? (
          <Button variant="ghost" size="sm" onClick={() => setRejections([])}>
            {tCommon('actions.dismiss')}
          </Button>
        ) : null}
      </div>
    </div>
  );
}

export interface UploadListProps {
  readonly items: readonly UploadItem[];
  readonly onCancel?: (id: string) => void;
  readonly onRetry?: (id: string) => void;
  readonly onRemove?: (id: string) => void;
  readonly title?: string;
}

/** Upload queue with per-item progress, cancel and retry affordances. */
export function UploadList({ items, onCancel, onRetry, onRemove, title }: UploadListProps) {
  const t = useTranslations('a11y');
  const tCommon = useTranslations('common');
  const tScan = useTranslations('scan');
  const tStates = useTranslations('states');
  const locale = useLocale();

  if (items.length === 0) return null;

  return (
    <section className={styles.list} aria-label={title ?? tScan('queue.title')}>
      {title ? <h3 className={styles.listTitle}>{title}</h3> : null}
      <ul className={styles.listItems}>
        {items.map((item) => {
          const statusMessage =
            item.status === 'uploading'
              ? t('upload.statusUploading', { name: item.name, percent: item.progress })
              : item.status === 'done'
                ? t('upload.statusDone', { name: item.name })
                : item.status === 'error'
                  ? t('upload.statusError', { name: item.name })
                  : item.status === 'cancelled'
                    ? t('upload.statusCancelled', { name: item.name })
                    : t('upload.statusQueued', { name: item.name });
          const tone =
            item.status === 'error'
              ? 'danger'
              : item.status === 'done'
                ? 'success'
                : item.status === 'cancelled'
                  ? 'warning'
                  : 'accent';
          return (
            <li key={item.id} className={styles.listItem}>
              <div className={styles.itemHead}>
                <span className={styles.itemName}>
                  <Icon name="file-text" size={15} />
                  {item.name}
                </span>
                <span className={styles.itemMeta}>{formatFileSize(item.size, locale)}</span>
              </div>
              {item.status === 'error' && item.error ? (
                <p className={styles.itemError}>{item.error}</p>
              ) : null}
              <ProgressBar
                value={item.progress}
                tone={tone}
                size="sm"
                label={statusMessage}
                showValue={item.status === 'uploading'}
              />
              <div className={styles.itemActions}>
                {item.status === 'uploading' || item.status === 'queued' ? (
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={() => onCancel?.(item.id)}
                    aria-label={t('upload.cancel', { name: item.name })}
                  >
                    {tCommon('actions.cancel')}
                  </Button>
                ) : null}
                {item.status === 'error' || item.status === 'cancelled' ? (
                  <Button variant="ghost" size="sm" onClick={() => onRetry?.(item.id)}>
                    {tScan('queue.retry')}
                  </Button>
                ) : null}
                {item.status === 'done' ||
                item.status === 'error' ||
                item.status === 'cancelled' ? (
                  <IconButton
                    label={t('upload.remove', { name: item.name })}
                    icon="x"
                    size="sm"
                    onClick={() => onRemove?.(item.id)}
                  />
                ) : null}
                <span className={styles.visuallyHidden} role="status" aria-live="polite">
                  {statusMessage}
                </span>
                {item.status === 'done' ? (
                  <span className={styles.visuallyHidden}>{tStates('success.saved')}</span>
                ) : null}
              </div>
            </li>
          );
        })}
      </ul>
    </section>
  );
}

/* -------------------------------------------------------------------------- */
/* Upload queue                                                                */
/* -------------------------------------------------------------------------- */

export interface UploadContext {
  readonly onProgress: (percent: number) => void;
  readonly signal: AbortSignal;
  readonly idempotencyKey: true;
}

export interface UploadQueueOptions {
  /** Performs the actual transfer; injected so the queue stays transport-agnostic. */
  readonly upload: (file: File, context: UploadContext) => Promise<void>;
  readonly onUploaded?: (file: File) => void;
  readonly onFailed?: (file: File, message: string) => void;
}

export interface UploadQueueApi {
  readonly items: readonly UploadItem[];
  readonly enqueue: (files: readonly File[]) => void;
  readonly cancel: (id: string) => void;
  readonly retry: (id: string) => void;
  readonly remove: (id: string) => void;
  readonly clear: () => void;
}

function queueId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return `upload-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
}

interface QueueEntry {
  readonly id: string;
  readonly file: File;
  readonly item: UploadItem;
}

/**
 * Orchestrates uploads with progress, cancellation and retry.
 *
 * The upload function is injected (`api.documents.upload` in production), which
 * keeps HTTP concerns in the API client and makes the queue unit-testable.
 */
export function useUploadQueue({
  upload,
  onUploaded,
  onFailed,
}: UploadQueueOptions): UploadQueueApi {
  const [entries, setEntries] = useState<readonly QueueEntry[]>([]);
  const controllers = useRef(new Map<string, AbortController>());

  const patch = useCallback((id: string, changes: Partial<UploadItem>) => {
    setEntries((current) =>
      current.map((entry) =>
        entry.id === id ? { ...entry, item: { ...entry.item, ...changes } } : entry,
      ),
    );
  }, []);

  const start = useCallback(
    async (entry: QueueEntry) => {
      const controller = new AbortController();
      controllers.current.set(entry.id, controller);
      patch(entry.id, { status: 'uploading', progress: 0, error: undefined });
      try {
        await upload(entry.file, {
          onProgress: (percent) => patch(entry.id, { progress: percent }),
          signal: controller.signal,
          idempotencyKey: true,
        });
        patch(entry.id, { status: 'done', progress: 100 });
        onUploaded?.(entry.file);
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        const aborted = controller.signal.aborted;
        patch(entry.id, {
          status: aborted ? 'cancelled' : 'error',
          error: aborted ? undefined : message,
        });
        if (!aborted) onFailed?.(entry.file, message);
      } finally {
        controllers.current.delete(entry.id);
      }
    },
    [onFailed, onUploaded, patch, upload],
  );

  const enqueue = useCallback(
    (files: readonly File[]) => {
      const created = files.map<QueueEntry>((file) => ({
        id: queueId(),
        file,
        item: { id: '', name: file.name, size: file.size, progress: 0, status: 'queued' },
      }));
      const withIds = created.map((entry) => ({ ...entry, item: { ...entry.item, id: entry.id } }));
      setEntries((current) => [...current, ...withIds]);
      for (const entry of withIds) void start(entry);
    },
    [start],
  );

  const cancel = useCallback((id: string) => {
    controllers.current.get(id)?.abort();
    setEntries((current) =>
      current.map((entry) =>
        entry.id === id && entry.item.status !== 'done'
          ? { ...entry, item: { ...entry.item, status: 'cancelled' } }
          : entry,
      ),
    );
  }, []);

  const retry = useCallback(
    (id: string) => {
      const entry = entries.find((candidate) => candidate.id === id);
      if (entry) void start(entry);
    },
    [entries, start],
  );

  const remove = useCallback((id: string) => {
    setEntries((current) => current.filter((entry) => entry.id !== id));
  }, []);

  const clear = useCallback(() => setEntries([]), []);

  useEffect(
    () => () => {
      for (const controller of controllers.current.values()) controller.abort();
      controllers.current.clear();
    },
    [],
  );

  const items = useMemo(() => entries.map((entry) => entry.item), [entries]);

  return { items, enqueue, cancel, retry, remove, clear };
}
