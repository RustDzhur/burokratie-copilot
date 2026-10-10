'use client';

import { type ReactNode } from 'react';
import { Icon, type IconName } from '@/components/icons';
import { isApiError, type ApiError } from '@/lib/api/errors';
import { useTranslations } from '@/i18n/provider';
import styles from './feedback.module.css';

export type Tone = 'neutral' | 'info' | 'success' | 'warning' | 'danger' | 'accent';

/* -------------------------------------------------------------------------- */
/* Spinner                                                                     */
/* -------------------------------------------------------------------------- */

export interface SpinnerProps {
  readonly size?: 'sm' | 'md' | 'lg';
  /** Accessible label; defaults to the localized "loading" message. */
  readonly label?: string;
  readonly className?: string;
}

export function Spinner({ size = 'md', label, className }: SpinnerProps) {
  const t = useTranslations('common');
  const accessibleLabel = label ?? t('labels.loading');
  return (
    <span
      className={[styles.spinner, styles[`spinner-${size}`], className].filter(Boolean).join(' ')}
    >
      <span className={styles.spinnerCircle} aria-hidden="true" />
      <span className={styles.visuallyHidden}>{accessibleLabel}</span>
    </span>
  );
}

/* -------------------------------------------------------------------------- */
/* Skeleton                                                                    */
/* -------------------------------------------------------------------------- */

export interface SkeletonProps {
  readonly width?: string | number;
  readonly height?: string | number;
  readonly radius?: string;
  readonly variant?: 'text' | 'block' | 'circle';
  readonly className?: string;
}

export function Skeleton({ width, height, radius, variant = 'block', className }: SkeletonProps) {
  return (
    <span
      aria-hidden="true"
      className={[styles.skeleton, styles[`skeleton-${variant}`], className]
        .filter(Boolean)
        .join(' ')}
      style={{
        width: typeof width === 'number' ? `${width}px` : width,
        height: typeof height === 'number' ? `${height}px` : height,
        borderRadius: radius,
      }}
    />
  );
}

export interface SkeletonTextProps {
  readonly lines?: number;
  readonly className?: string;
}

export function SkeletonText({ lines = 3, className }: SkeletonTextProps) {
  return (
    <span className={[styles.skeletonText, className].filter(Boolean).join(' ')}>
      {Array.from({ length: lines }, (_, index) => (
        <Skeleton
          key={index}
          variant="text"
          width={index === lines - 1 ? '62%' : '100%'}
          height="0.75rem"
        />
      ))}
    </span>
  );
}

export interface SkeletonTableProps {
  readonly rows?: number;
  readonly columns?: number;
  readonly label?: string;
}

export function SkeletonTable({ rows = 6, columns = 5, label }: SkeletonTableProps) {
  const t = useTranslations('states');
  const accessibleLabel = label ?? t('skeleton.tableLoading');
  return (
    <div className={styles.skeletonTable} role="status" aria-busy="true">
      <span className={styles.visuallyHidden}>{accessibleLabel}</span>
      <div className={styles.skeletonTableHead} aria-hidden="true">
        {Array.from({ length: columns }, (_, index) => (
          <Skeleton key={index} height="0.875rem" width="70%" />
        ))}
      </div>
      {Array.from({ length: rows }, (_, rowIndex) => (
        <div key={rowIndex} className={styles.skeletonTableRow} aria-hidden="true">
          {Array.from({ length: columns }, (_, columnIndex) => (
            <Skeleton
              key={columnIndex}
              height="0.75rem"
              width={columnIndex === 0 ? '85%' : '60%'}
            />
          ))}
        </div>
      ))}
    </div>
  );
}

export interface SkeletonCardsProps {
  readonly count?: number;
  readonly label?: string;
}

export function SkeletonCards({ count = 4, label }: SkeletonCardsProps) {
  const t = useTranslations('states');
  return (
    <div className={styles.skeletonCards} role="status" aria-busy="true">
      <span className={styles.visuallyHidden}>{label ?? t('loadingRegion')}</span>
      {Array.from({ length: count }, (_, index) => (
        <div key={index} className={styles.skeletonCard} aria-hidden="true">
          <Skeleton height="0.875rem" width="45%" />
          <Skeleton height="1.75rem" width="30%" />
          <SkeletonText lines={2} />
        </div>
      ))}
    </div>
  );
}

/* -------------------------------------------------------------------------- */
/* EmptyState                                                                  */
/* -------------------------------------------------------------------------- */

export interface EmptyStateProps {
  readonly icon?: IconName;
  readonly title: string;
  readonly description?: string;
  readonly actions?: ReactNode;
  readonly hint?: string;
  readonly size?: 'sm' | 'md' | 'lg';
  readonly className?: string;
}

export function EmptyState({
  icon = 'inbox',
  title,
  description,
  actions,
  hint,
  size = 'md',
  className,
}: EmptyStateProps) {
  return (
    <div
      className={[styles.stateBox, styles[`stateBox-${size}`], className].filter(Boolean).join(' ')}
      data-state="empty"
    >
      <span className={styles.stateIcon} aria-hidden="true">
        <Icon name={icon} size={size === 'sm' ? 20 : 26} />
      </span>
      <p className={styles.stateTitle}>{title}</p>
      {description ? <p className={styles.stateDescription}>{description}</p> : null}
      {hint ? <p className={styles.stateHint}>{hint}</p> : null}
      {actions ? <div className={styles.stateActions}>{actions}</div> : null}
    </div>
  );
}

/* -------------------------------------------------------------------------- */
/* ErrorState                                                                  */
/* -------------------------------------------------------------------------- */

function resolveApiError(error: unknown): ApiError | null {
  if (isApiError(error)) return error;
  if (error instanceof Error) return null;
  return null;
}

export interface ErrorStateProps {
  /** Prefer an `ApiError`; any other value falls back to a generic message. */
  readonly error?: unknown;
  /** Overrides the title derived from the error kind. */
  readonly title?: string;
  readonly description?: string;
  readonly onRetry?: () => void;
  readonly retryLabel?: string;
  readonly actions?: ReactNode;
  readonly compact?: boolean;
  readonly className?: string;
}

export function ErrorState({
  error,
  title,
  description,
  onRetry,
  retryLabel,
  actions,
  compact = false,
  className,
}: ErrorStateProps) {
  const tErrors = useTranslations('errors');
  const tStates = useTranslations('states');
  const tCommon = useTranslations('common');
  const apiError = resolveApiError(error);

  const kind = apiError?.kind ?? 'unknown';
  const resolvedTitle = title ?? (apiError ? tErrors(kind) : tStates('error.title'));
  const resolvedDescription =
    description ?? (apiError ? tStates('error.description') : tStates('error.description'));

  return (
    <div
      className={[
        styles.stateBox,
        compact ? styles['stateBox-sm'] : '',
        styles.stateBoxError,
        className,
      ]
        .filter(Boolean)
        .join(' ')}
      data-state="error"
      role="alert"
    >
      <span className={[styles.stateIcon, styles.stateIconError].join(' ')} aria-hidden="true">
        <Icon
          name={kind === 'network' || kind === 'timeout' ? 'alert-triangle' : 'alert-circle'}
          size={compact ? 20 : 26}
        />
      </span>
      <p className={styles.stateTitle}>{resolvedTitle}</p>
      <p className={styles.stateDescription}>{resolvedDescription}</p>
      {kind === 'network' || kind === 'timeout' ? (
        <p className={styles.stateHint}>{tStates('offline.hint')}</p>
      ) : (
        <p className={styles.stateHint}>{tStates('error.hint')}</p>
      )}
      {apiError?.correlationId ? (
        <p className={styles.correlation}>
          <span>{tStates('error.correlationId')}:</span>{' '}
          <code className={styles.correlationValue}>{apiError.correlationId}</code>
        </p>
      ) : null}
      {onRetry || actions ? (
        <div className={styles.stateActions}>
          {onRetry ? (
            <button type="button" className={styles.retryButton} onClick={onRetry}>
              <Icon name="refresh" size={16} />
              {retryLabel ?? tCommon('actions.retry')}
            </button>
          ) : null}
          {actions}
        </div>
      ) : null}
    </div>
  );
}

/* -------------------------------------------------------------------------- */
/* InlineAlert                                                                 */
/* -------------------------------------------------------------------------- */

export interface InlineAlertProps {
  readonly tone?: Tone;
  readonly title?: string;
  readonly children?: ReactNode;
  readonly icon?: IconName;
  readonly actions?: ReactNode;
  readonly live?: 'polite' | 'assertive' | 'off';
  readonly className?: string;
}

const TONE_ICONS: Record<Tone, IconName> = {
  neutral: 'info',
  info: 'info',
  success: 'check-circle',
  warning: 'alert-triangle',
  danger: 'alert-circle',
  accent: 'sparkles',
};

export function InlineAlert({
  tone = 'info',
  title,
  children,
  icon,
  actions,
  live = 'polite',
  className,
}: InlineAlertProps) {
  return (
    <div
      className={[styles.alert, styles[`alert-${tone}`], className].filter(Boolean).join(' ')}
      role={tone === 'danger' ? 'alert' : 'status'}
      aria-live={live === 'off' ? undefined : live}
    >
      <span className={styles.alertIcon} aria-hidden="true">
        <Icon name={icon ?? TONE_ICONS[tone]} size={18} />
      </span>
      <div className={styles.alertBody}>
        {title ? <p className={styles.alertTitle}>{title}</p> : null}
        {children ? <div className={styles.alertText}>{children}</div> : null}
      </div>
      {actions ? <div className={styles.alertActions}>{actions}</div> : null}
    </div>
  );
}

/* -------------------------------------------------------------------------- */
/* ProgressBar                                                                 */
/* -------------------------------------------------------------------------- */

export interface ProgressBarProps {
  readonly value?: number;
  readonly max?: number;
  readonly label?: string;
  readonly indeterminate?: boolean;
  readonly tone?: 'accent' | 'success' | 'danger' | 'warning';
  readonly showValue?: boolean;
  readonly size?: 'sm' | 'md';
  readonly className?: string;
}

export function ProgressBar({
  value = 0,
  max = 100,
  label,
  indeterminate = false,
  tone = 'accent',
  showValue = false,
  size = 'md',
  className,
}: ProgressBarProps) {
  const t = useTranslations('a11y');
  const percent = max > 0 ? Math.min(100, Math.max(0, Math.round((value / max) * 100))) : 0;
  const accessibleLabel = label ?? t('progress.label');
  return (
    <div className={[styles.progress, className].filter(Boolean).join(' ')}>
      {label || showValue ? (
        <div className={styles.progressHeader}>
          <span className={styles.progressLabel}>{label}</span>
          {showValue ? <span className={styles.progressValue}>{percent}%</span> : null}
        </div>
      ) : null}
      <div
        className={[styles.progressTrack, styles[`progressTrack-${size}`]].join(' ')}
        role="progressbar"
        aria-label={accessibleLabel}
        aria-valuemin={0}
        aria-valuemax={max}
        aria-valuenow={indeterminate ? undefined : value}
        aria-valuetext={indeterminate ? t('progress.label') : t('progress.value', { percent })}
        aria-busy={indeterminate || undefined}
      >
        <span
          className={[
            styles.progressFill,
            styles[`progressFill-${tone}`],
            indeterminate ? styles.progressIndeterminate : '',
          ]
            .filter(Boolean)
            .join(' ')}
          style={indeterminate ? undefined : { width: `${percent}%` }}
        />
      </div>
    </div>
  );
}

/* -------------------------------------------------------------------------- */
/* Live region                                                                 */
/* -------------------------------------------------------------------------- */

export interface LiveRegionProps {
  readonly message: string;
  readonly politeness?: 'polite' | 'assertive';
}

/** Standalone live region for one-off announcements. */
export function LiveRegion({ message, politeness = 'polite' }: LiveRegionProps) {
  const t = useTranslations('a11y');
  return (
    <div
      className={styles.visuallyHidden}
      role={politeness === 'assertive' ? 'alert' : 'status'}
      aria-live={politeness}
      aria-atomic="true"
      aria-label={politeness === 'assertive' ? t('liveRegion.assertive') : t('liveRegion.polite')}
    >
      {message}
    </div>
  );
}
