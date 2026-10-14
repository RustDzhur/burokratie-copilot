'use client';

import Link from 'next/link';
import { useEffect, useState } from 'react';
import { Badge, Card, CardBody, CardHeader } from '@/components/ui';
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

  const { state } = useApiResource<ProcessingFlow>(
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

  if (state.status === 'loading') {
    return (
      <Card>
        <CardBody>
          <SkeletonText lines={3} />
        </CardBody>
      </Card>
    );
  }
  if (state.status !== 'ready') {
    return null;
  }

  const flow = state.data;
  const outcome = flow.outcome;
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
        description={outcome.explanation ?? outcome.next}
        icon="sparkles"
        headingLevel={2}
      />
      <CardBody>
        {outcome.result === 'WAITING_FOR_REVIEW' ? (
          <InlineAlert tone="warning" title={t('flow.review.title')}>
            <Link href="/review">{t('flow.review.link')}</Link>
          </InlineAlert>
        ) : null}
        {outcome.result === 'FAILED' ? (
          <InlineAlert tone="danger" title={t('processing.failed')}>
            {outcome.next}
          </InlineAlert>
        ) : null}
        {outcome.result === 'TASK_CREATED' && outcome.assignmentPending ? (
          <InlineAlert tone="warning" title={t('flow.unassigned')}>
            <Link href="/tasks">{t('flow.openTask')}</Link>
          </InlineAlert>
        ) : null}

        <div className={styles.facts}>
          {flow.documentTypeName ? (
            <Badge tone="accent" variant="outline">
              {flow.documentTypeName}
              {typeof flow.confidence === 'number'
                ? ` · ${formatPercent(flow.confidence, locale)}`
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
