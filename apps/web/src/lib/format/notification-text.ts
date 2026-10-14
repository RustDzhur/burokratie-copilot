'use client';

import { useCallback } from 'react';
import { useGlobalTranslations, useLocale, useTranslations } from '@/i18n/provider';
import type { NotificationSummary } from '@/lib/api/types';

/**
 * The text of a notification in the language the person has chosen.
 *
 * The backend says what happened and what it is about (the kind of message, the file, the document type in all three
 * languages, the threshold, the step); the sentence is built here, from the catalogue of the interface language. A
 * sentence written by the backend in one language was shown to a Russian-speaking user in English. A notification of
 * a kind this client does not know keeps the text the backend stored, so nothing is lost.
 */
export function useNotificationText(): (notification: NotificationSummary) => {
  readonly title: string;
  readonly body: string | null;
} {
  const t = useTranslations('notificationText');
  const global = useGlobalTranslations();
  const locale = useLocale();

  return useCallback(
    (notification) => {
      const facts = notification.facts ?? {};
      const text = (key: string): string | undefined => {
        const value = facts[key];
        return typeof value === 'string' ? value : undefined;
      };
      const number = (key: string): number | undefined => {
        const value = facts[key];
        return typeof value === 'number' ? value : undefined;
      };
      const names = facts.typeName as Readonly<Record<string, string>> | undefined;
      const type = names?.[locale] ?? names?.de;
      const filename = text('filename') ?? '';
      const task = text('taskTitle') ?? '';
      const fallback = { title: notification.title, body: notification.body ?? null };

      switch (notification.kind) {
        case 'document.action_required': {
          const parts = [t('actionRequired.body', { filename })];
          const due = text('dueDate');
          if (due) parts.push(t('actionRequired.due', { date: due }));
          if (facts.unassigned === true) parts.push(t('actionRequired.unassigned'));
          return {
            title: type ? t('actionRequired.title', { type }) : t('actionRequired.titleNoType'),
            body: parts.join(' '),
          };
        }
        case 'document.review_required': {
          const kind = text('reviewKind');
          const values = {
            filename,
            type: type ?? '',
            confidence: number('confidence') ?? 0,
            threshold: number('acceptThreshold') ?? 85,
          };
          const body =
            kind === 'LOW_CONFIDENCE'
              ? t('reviewRequired.LOW_CONFIDENCE', values)
              : kind === 'TYPE_ALWAYS_REVIEW'
                ? t('reviewRequired.TYPE_ALWAYS_REVIEW', values)
                : t('reviewRequired.NO_MATCH', values);
          return { title: t('reviewRequired.title'), body };
        }
        case 'document.processing_stopped': {
          const step = text('step') ?? '';
          const stepLabel = global('documentViewer', `flow.step.${step}` as 'flow.step.OCR');
          return {
            title: t('processingStopped.title'),
            body: t('processingStopped.body', {
              filename,
              step: stepLabel.startsWith('flow.step.') ? step : stepLabel,
              reason: text('reason') ?? '',
            }),
          };
        }
        case 'document.duplicate_detected':
          return { title: t('duplicate.title'), body: t('duplicate.body', { filename }) };
        case 'task.overdue':
          return { title: t('taskOverdue.title'), body: t('taskOverdue.body', { task }) };
        case 'task.reminder':
          return { title: t('taskReminder.title'), body: t('taskReminder.body', { task }) };
        case 'task.unassigned':
          return { title: t('taskUnassigned.title'), body: t('taskUnassigned.body', { task }) };
        default:
          return fallback;
      }
    },
    [t, global, locale],
  );
}
