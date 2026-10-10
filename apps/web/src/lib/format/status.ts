'use client';

import { useCallback } from 'react';
import { useTranslations } from '@/i18n/provider';
import type { Namespace } from '@/i18n/messages';

/**
 * Status presentation helpers.
 *
 * Statuses come from the backend as machine values (`needs_review`,
 * `retention_expired`, …). Two rules apply everywhere (docs/task.md §23.1):
 * colour is never the only carrier of meaning, and an unknown status must never
 * blank out a screen — it degrades to its machine name with a neutral tone.
 */

export type StatusTone = 'neutral' | 'info' | 'success' | 'warning' | 'danger' | 'accent';

const STATUS_TONES: Record<string, StatusTone> = {
  // success
  processed: 'success',
  done: 'success',
  paid: 'success',
  approved: 'success',
  active: 'success',
  connected: 'success',
  success: 'success',
  closed: 'success',
  matched: 'success',
  published: 'success',
  read: 'success',
  retention_active: 'success',
  // info / in progress
  new: 'info',
  received: 'info',
  processing: 'info',
  open: 'info',
  todo: 'info',
  doing: 'info',
  in_progress: 'info',
  running: 'info',
  unread: 'info',
  invited: 'info',
  'in review': 'info',
  review: 'warning',
  // warning
  needs_review: 'warning',
  expiring: 'warning',
  pending: 'warning',
  suggested: 'warning',
  waiting: 'warning',
  paused: 'warning',
  due: 'warning',
  retention_expired: 'warning',
  needs_reply: 'warning',
  // danger
  failed: 'danger',
  error: 'danger',
  overdue: 'danger',
  blocked: 'danger',
  denied: 'danger',
  // neutral
  draft: 'neutral',
  archived: 'neutral',
  ignored: 'neutral',
  disabled: 'neutral',
  off: 'neutral',
  ended: 'neutral',
  unknown: 'neutral',
};

export function statusTone(status: string | null | undefined): StatusTone {
  if (!status) return 'neutral';
  return STATUS_TONES[status] ?? 'neutral';
}

/**
 * Returns a translator for `status.*` keys of a namespace, falling back to the
 * raw machine value when a translation is missing.
 */
export function useStatusLabels<N extends Namespace>(namespace: N): (status: string) => string {
  const t = useTranslations(namespace);
  return useCallback(
    (status: string) => {
      if (!status) return '';
      const translate = t as unknown as (key: string) => string;
      const label = translate(`status.${status}`);
      return label === '' ? status : label;
    },
    [t],
  );
}

/** Same idea for `type.*`, `priority.*`, `channel.*`, `category.*` groups. */
export function useGroupedLabels<N extends Namespace>(
  namespace: N,
  group: string,
): (value: string) => string {
  const t = useTranslations(namespace);
  return useCallback(
    (value: string) => {
      if (!value) return '';
      const translate = t as unknown as (key: string) => string;
      const label = translate(`${group}.${value}`);
      return label === '' ? value : label;
    },
    [t, group],
  );
}
