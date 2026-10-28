'use client';

import type { ReactNode } from 'react';
import { Breadcrumbs } from '@/components/ui/data';
import { ErrorState, SkeletonCards, SkeletonTable } from '@/components/ui/feedback';
import type { ResourceState } from '@/lib/api/hooks';
import styles from './resource-view.module.css';

export interface PageHeaderProps {
  readonly title: string;
  readonly subtitle?: string;
  readonly actions?: ReactNode;
  readonly breadcrumbs?: readonly { readonly label: string; readonly href?: string }[];
  readonly meta?: ReactNode;
  readonly headingLevel?: 1 | 2;
}

/**
 * Standard page header: breadcrumb trail, single `<h1>` per screen, description
 * and the primary actions. Keeps the heading hierarchy identical everywhere.
 */
export function PageHeader({
  title,
  subtitle,
  actions,
  breadcrumbs,
  meta,
  headingLevel = 1,
}: PageHeaderProps) {
  const Heading = `h${headingLevel}` as 'h1' | 'h2';
  return (
    <header className={styles.pageHeader}>
      {breadcrumbs && breadcrumbs.length > 0 ? <Breadcrumbs items={breadcrumbs} /> : null}
      <div className={styles.pageHeaderRow}>
        <div className={styles.pageHeaderText}>
          <Heading className={styles.pageTitle}>{title}</Heading>
          {subtitle ? <p className={styles.pageSubtitle}>{subtitle}</p> : null}
          {meta ? <div className={styles.pageMeta}>{meta}</div> : null}
        </div>
        {actions ? <div className={styles.pageActions}>{actions}</div> : null}
      </div>
    </header>
  );
}

export interface PageSectionProps {
  readonly title?: string;
  readonly description?: string;
  readonly actions?: ReactNode;
  readonly children: ReactNode;
  readonly className?: string;
}

export function PageSection({
  title,
  description,
  actions,
  children,
  className,
}: PageSectionProps) {
  return (
    <section className={[styles.section, className].filter(Boolean).join(' ')}>
      {title ? (
        <div className={styles.sectionHeader}>
          <div>
            <h2 className={styles.sectionTitle}>{title}</h2>
            {description ? <p className={styles.sectionDescription}>{description}</p> : null}
          </div>
          {actions ? <div className={styles.sectionActions}>{actions}</div> : null}
        </div>
      ) : null}
      {children}
    </section>
  );
}

export interface ResourceViewProps<T> {
  readonly state: ResourceState<T>;
  readonly children: (data: T) => ReactNode;
  /** Decides whether loaded data counts as "empty" for this screen. */
  readonly isEmpty?: (data: T) => boolean;
  readonly emptyState: ReactNode;
  readonly skeleton?: ReactNode;
  readonly onRetry?: () => void;
  readonly isRefreshing?: boolean;
}

/**
 * Renders exactly one of the four mandatory screen states
 * (docs/task.md §29): loading → error → empty → content. Screens cannot
 * accidentally render a blank page, because `emptyState` is required and the
 * error branch is built in.
 */
export function ResourceView<T>({
  state,
  children,
  isEmpty,
  emptyState,
  skeleton,
  onRetry,
  isRefreshing = false,
}: ResourceViewProps<T>) {
  if (state.status === 'loading') {
    return (
      <div className={styles.stateSlot} aria-busy="true">
        {skeleton ?? <SkeletonTable rows={6} columns={5} />}
      </div>
    );
  }

  if (state.status === 'error') {
    return (
      <div className={styles.stateSlot}>
        <ErrorState error={state.error} onRetry={onRetry} />
      </div>
    );
  }

  if (isEmpty?.(state.data)) {
    return (
      <div className={styles.stateSlot} data-refreshing={isRefreshing || undefined}>
        {emptyState}
      </div>
    );
  }

  return (
    <div className={styles.contentSlot} data-refreshing={isRefreshing || undefined}>
      {children(state.data)}
    </div>
  );
}

/** Loading placeholder for dashboard-style card grids. */
export function CardsSkeleton({ count = 4 }: { count?: number }) {
  return <SkeletonCards count={count} />;
}
