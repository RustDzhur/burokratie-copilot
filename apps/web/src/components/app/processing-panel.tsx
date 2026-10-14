'use client';

import Link from 'next/link';
import { useEffect, useRef, useState } from 'react';
import { Badge, Card, CardBody, CardHeader } from '@/components/ui';
import { Button } from '@/components/ui/button';
import { useToast } from '@/components/ui/toast';
import { InlineAlert, SkeletonText } from '@/components/ui/feedback';
import { formatDateTime, formatPercent } from '@/i18n/format';
import { useLocale, useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { useApiResource } from '@/lib/api/hooks';
import type { ProcessingFlow, ProcessingStep } from '@/lib/api/processing';
import styles from './processing-panel.module.css';

const STEP_KEYS = {
  SECURITY_SCAN: 'flow.step.SECURITY_SCAN',
  HASH: 'flow.step.HASH',
  DUPLICATE_CHECK: 'flow.step.DUPLICATE_CHECK',
  FORMAT_DETECT: 'flow.step.FORMAT_DETECT',
  TEXT_EXTRACT: 'flow.step.TEXT_EXTRACT',
  OCR: 'flow.step.OCR',
  LANGUAGE_DETECT: 'flow.step.LANGUAGE_DETECT',
  CLASSIFY: 'flow.step.CLASSIFY',
  FIELD_EXTRACT: 'flow.step.FIELD_EXTRACT',
  MATCHING: 'flow.step.MATCHING',
} as const;

const STATUS_KEYS = {
  SUCCEEDED: 'flow.status.SUCCEEDED',
  FAILED: 'flow.status.FAILED',
  SKIPPED: 'flow.status.SKIPPED',
  RUNNING: 'flow.status.RUNNING',
  PENDING: 'flow.status.PENDING',
} as const;

function statusTone(status: string): 'success' | 'danger' | 'warning' | 'info' | 'neutral' {
  switch (status) {
    case 'SUCCEEDED':
      return 'success';
    case 'FAILED':
      return 'danger';
    case 'SKIPPED':
      return 'warning';
    case 'RUNNING':
      return 'info';
    default:
      return 'neutral';
  }
}

interface ProcessingPanelProps {
  readonly documentId: string;
  /** Changes when the person starts the processing again, so the panel reads the new state at once. */
  readonly reloadKey?: number;
}

/**
 * What is happening with this document, read from what the platform stored: which steps ran, what it
 * recognised, and what it did about it (a task, a case, a contract card) or why it did nothing.
 *
 * It follows a document that is still being processed every few seconds and stops when the matter is settled,
 * so an upload is never followed by a silent page.
 */
export function ProcessingPanel({ documentId, reloadKey = 0 }: ProcessingPanelProps) {
  const t = useTranslations('documentViewer');
  const locale = useLocale();
  const [polling, setPolling] = useState(true);
  const [creating, setCreating] = useState(false);
  const toast = useToast();

  const { state, reload } = useApiResource<ProcessingFlow>(
    (signal) => api.documents.pipeline(documentId, signal),
    [documentId, reloadKey],
    { keepPreviousData: true, pollMs: polling ? 3000 : undefined },
  );

  useEffect(() => {
    if (state.status === 'ready') {
      setPolling(state.data.outcome.result === 'PROCESSING');
    }
  }, [state]);

  useEffect(() => {
    setPolling(true);
  }, [reloadKey]);

  // A poll that fails or is still on its way must not take the panel away: the last known state stays on screen.
  const lastFlow = useRef<ProcessingFlow | null>(null);
  const lastDocument = useRef(documentId);
  if (lastDocument.current !== documentId) {
    lastDocument.current = documentId;
    lastFlow.current = null;
  }
  if (state.status === 'ready') {
    lastFlow.current = state.data;
  }
  const shown: ProcessingFlow | null = state.status === 'ready' ? state.data : lastFlow.current;

  if (shown === null && state.status === 'loading') {
    return (
      <Card>
        <CardBody>
          <SkeletonText lines={3} />
        </CardBody>
      </Card>
    );
  }
  if (shown === null) {
    return null;
  }

  const flow = shown;
  const outcome = flow.outcome;
  // Every sentence below is built here from facts, in the interface language; the backend's own sentences are
  // German and are only the fallback for a result this client does not know.
  const typeLabel =
    flow.typeNames?.[locale] ?? flow.typeNames?.de ?? flow.documentTypeName ?? flow.suggestedType?.name ?? null;
  const resultText = ((): string => {
    switch (outcome.result) {
      case 'TASK_CREATED':
        return [
          t('flow.out.TASK_CREATED', { type: typeLabel ?? t('flow.out.unknownType'), task: outcome.taskTitle ?? '' }),
          outcome.assignmentPending ? t('flow.out.unassigned') : '',
          outcome.humanReview ? t('flow.out.humanReview') : '',
        ]
          .filter(Boolean)
          .join(' ');
      case 'FILE_ONLY':
        return t('flow.out.FILE_ONLY', { type: typeLabel ?? t('flow.out.unknownType') });
      case 'ARCHIVED_OLD':
        return outcome.statedDue
          ? t('flow.out.ARCHIVED_OLD_DUE', {
              type: typeLabel ?? t('flow.out.unknownType'),
              date: outcome.documentDate ?? '—',
              due: outcome.statedDue,
            })
          : t('flow.out.ARCHIVED_OLD', {
              type: typeLabel ?? t('flow.out.unknownType'),
              date: outcome.documentDate ?? outcome.periodTo ?? '—',
            });
      case 'PROCESSING':
        return t('flow.out.PROCESSING');
      case 'WAITING_FOR_REVIEW':
        return t('flow.out.WAITING_FOR_REVIEW');
      case 'DUPLICATE':
        return t('flow.out.DUPLICATE');
      case 'FAILED':
        return t('flow.out.FAILED');
      default:
        return outcome.explanation ?? outcome.next;
    }
  })();
  const reasonText =
    flow.reviewKind === 'LOW_CONFIDENCE'
      ? t('flow.reason.LOW_CONFIDENCE', {
          type: typeLabel ?? '',
          confidence: Math.round((flow.suggestedType?.confidence ?? 0) * 100),
          threshold: flow.acceptThreshold ?? 85,
        })
      : flow.reviewKind === 'TYPE_ALWAYS_REVIEW'
        ? t('flow.reason.TYPE_ALWAYS_REVIEW', { type: typeLabel ?? '' })
        : flow.reviewKind === 'NO_MATCH'
          ? t('flow.reason.NO_MATCH')
          : (flow.reviewReason ?? null);
  const stepLabel = (step: ProcessingStep): string => {
    const key = STEP_KEYS[step.step as keyof typeof STEP_KEYS];
    return key ? t(key) : step.step;
  };
  const statusLabel = (status: string): string => {
    const key = STATUS_KEYS[status as keyof typeof STATUS_KEYS];
    return key ? t(key) : status;
  };

  return (
    <Card>
      <CardHeader
        title={t('flow.title')}
        description={resultText}
        icon="sparkles"
        headingLevel={2}
      />
      <CardBody>
        {outcome.result === 'WAITING_FOR_REVIEW' ? (
          <InlineAlert tone="warning" title={t('flow.review.title')}>
            {reasonText ? <p>{reasonText}</p> : null}
            <Link href="/review">{t('flow.review.link')}</Link>
          </InlineAlert>
        ) : null}
        {outcome.result === 'FAILED' ? (
          <InlineAlert tone="danger" title={t('processing.failed')}>
            {outcome.next}
          </InlineAlert>
        ) : null}
        {outcome.result === 'ARCHIVED_OLD' ? (
          <p>
            <Button
              variant="secondary"
              loading={creating}
              onClick={() => {
                setCreating(true);
                void api.documents
                  .createTask(documentId)
                  .then(() => {
                    toast.success(t('flow.createTask.done'));
                    reload();
                  })
                  .catch(() => toast.error(t('flow.createTask.failed')))
                  .finally(() => setCreating(false));
              }}
            >
              {t('flow.createTask')}
            </Button>
          </p>
        ) : null}
        {outcome.result === 'TASK_CREATED' && outcome.assignmentPending ? (
          <InlineAlert tone="warning" title={t('flow.unassigned')}>
            <Link href="/tasks">{t('flow.openTask')}</Link>
          </InlineAlert>
        ) : null}

        <div className={styles.facts}>
          {flow.documentTypeName ? (
            <Badge tone="accent" variant="outline">
              {typeLabel ?? flow.documentTypeName}
              {typeof flow.confidence === 'number'
                ? ` · ${formatPercent(flow.confidence, locale)}`
                : ''}
            </Badge>
          ) : null}
          {!flow.documentTypeName && flow.suggestedType ? (
            <Badge tone="warning" variant="outline">
              {t('flow.suggested')}: {typeLabel ?? flow.suggestedType.name}
              {typeof flow.suggestedType.confidence === 'number'
                ? ` · ${formatPercent(flow.suggestedType.confidence, locale)}`
                : ''}
            </Badge>
          ) : null}
          {outcome.priority ? <Badge tone="neutral">{outcome.priority}</Badge> : null}
          {outcome.dueAt ? (
            <span className={styles.meta}>
              {t('flow.dueAt')}: {formatDateTime(outcome.dueAt, locale)}
            </span>
          ) : null}
        </div>

        {outcome.result === 'TASK_CREATED' ? (
          <p className={styles.links}>
            <strong>{outcome.taskTitle}</strong>
            <Link href="/tasks">{t('flow.openTask')}</Link>
            {outcome.caseId ? <Link href="/cases">{t('flow.openCase')}</Link> : null}
            {outcome.contractId ? <Link href="/contracts">{t('flow.openContract')}</Link> : null}
          </p>
        ) : null}

        {flow.matchedPassages.length > 0 && !flow.documentTypeName ? (
          <div className={styles.passages}>
            <strong>{t('flow.foundInText')}:</strong>
            <ul>
              {flow.matchedPassages.map((passage) => (
                <li key={passage.pattern}>
                  <q>{passage.snippet}</q>
                </li>
              ))}
            </ul>
          </div>
        ) : null}

        {flow.textPreview ? (
          <details className={styles.preview}>
            <summary>{t('flow.textPreview')}</summary>
            <pre className={styles.previewText}>{flow.textPreview}</pre>
          </details>
        ) : null}

        <ol className={styles.steps} aria-label={t('flow.title')}>
          {flow.steps.map((step) => (
            <li key={step.step} className={styles.step}>
              <span className={styles.stepName}>{stepLabel(step)}</span>
              <Badge tone={statusTone(step.status)} variant="outline">
                {statusLabel(step.status)}
              </Badge>
              {step.error ? <span className={styles.stepError}>{step.error}</span> : null}
            </li>
          ))}
        </ol>
      </CardBody>
    </Card>
  );
}
