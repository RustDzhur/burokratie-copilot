'use client';

import Link from 'next/link';
import type { ReactNode } from 'react';
import { Icon, type IconName } from '@/components/icons';
import { useTranslations } from '@/i18n/provider';
import { statusTone, type StatusTone } from '@/lib/format/status';
import { SkeletonTable } from './feedback';
import styles from './data.module.css';

export type { StatusTone };

/* -------------------------------------------------------------------------- */
/* Card                                                                        */
/* -------------------------------------------------------------------------- */

export interface CardProps {
  readonly children: ReactNode;
  readonly as?: 'section' | 'article' | 'div' | 'li';
  readonly tone?: 'default' | 'glass' | 'accent' | 'danger';
  readonly padding?: 'none' | 'sm' | 'md' | 'lg';
  readonly interactive?: boolean;
  readonly className?: string;
  readonly 'aria-labelledby'?: string;
}

export function Card({
  children,
  as: Tag = 'section',
  tone = 'default',
  padding = 'md',
  interactive = false,
  className,
}: CardProps) {
  return (
    <Tag
      className={[
        styles.card,
        styles[`card-${tone}`],
        styles[`cardPadding-${padding}`],
        interactive ? styles.cardInteractive : '',
        className,
      ]
        .filter(Boolean)
        .join(' ')}
    >
      {children}
    </Tag>
  );
}

export interface CardHeaderProps {
  readonly title: ReactNode;
  readonly description?: ReactNode;
  readonly icon?: IconName;
  readonly actions?: ReactNode;
  readonly titleId?: string;
  readonly headingLevel?: 2 | 3 | 4;
  readonly badge?: ReactNode;
}

export function CardHeader({
  title,
  description,
  icon,
  actions,
  titleId,
  headingLevel = 3,
  badge,
}: CardHeaderProps) {
  const Heading = `h${headingLevel}` as 'h2' | 'h3' | 'h4';
  return (
    <header className={styles.cardHeader}>
      <div className={styles.cardHeaderMain}>
        {icon ? (
          <span className={styles.cardIcon} aria-hidden="true">
            <Icon name={icon} size={18} />
          </span>
        ) : null}
        <div className={styles.cardHeaderText}>
          <Heading className={styles.cardTitle} id={titleId}>
            {title}
            {badge ? <span className={styles.cardBadgeSlot}>{badge}</span> : null}
          </Heading>
          {description ? <p className={styles.cardDescription}>{description}</p> : null}
        </div>
      </div>
      {actions ? <div className={styles.cardActions}>{actions}</div> : null}
    </header>
  );
}

export function CardBody({ children, className }: { children: ReactNode; className?: string }) {
  return <div className={[styles.cardBody, className].filter(Boolean).join(' ')}>{children}</div>;
}

export function CardFooter({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <footer className={[styles.cardFooter, className].filter(Boolean).join(' ')}>{children}</footer>
  );
}

/* -------------------------------------------------------------------------- */
/* Badge & StatusPill                                                          */
/* -------------------------------------------------------------------------- */

export interface BadgeProps {
  readonly children: ReactNode;
  readonly tone?: StatusTone;
  readonly variant?: 'subtle' | 'solid' | 'outline';
  readonly icon?: IconName;
  readonly size?: 'sm' | 'md';
  readonly className?: string;
}

export function Badge({
  children,
  tone = 'neutral',
  variant = 'subtle',
  icon,
  size = 'sm',
  className,
}: BadgeProps) {
  return (
    <span
      className={[
        styles.badge,
        styles[`badge-${tone}-${variant}`],
        styles[`badgeSize-${size}`],
        className,
      ]
        .filter(Boolean)
        .join(' ')}
    >
      {icon ? <Icon name={icon} size={13} /> : null}
      <span>{children}</span>
    </span>
  );
}

export interface StatusPillProps {
  /** Localized label — mandatory, so status is never conveyed by colour alone. */
  readonly label: string;
  readonly tone?: StatusTone;
  /** Machine status used to derive the tone when `tone` is omitted. */
  readonly status?: string;
  readonly icon?: IconName;
  readonly className?: string;
}

export function StatusPill({ label, tone, status, icon, className }: StatusPillProps) {
  const resolvedTone = tone ?? statusTone(status ?? label);
  return (
    <span
      className={[styles.statusPill, styles[`statusPill-${resolvedTone}`], className]
        .filter(Boolean)
        .join(' ')}
      data-tone={resolvedTone}
    >
      <span className={styles.statusDot} aria-hidden="true" />
      {icon ? <Icon name={icon} size={13} /> : null}
      <span>{label}</span>
    </span>
  );
}

/* -------------------------------------------------------------------------- */
/* Avatar                                                                      */
/* -------------------------------------------------------------------------- */

export interface AvatarProps {
  readonly name: string;
  readonly src?: string | null;
  readonly size?: 'sm' | 'md' | 'lg';
  readonly status?: 'online' | 'away' | 'offline';
  readonly className?: string;
}

function initials(name: string): string {
  const parts = name.trim().split(/\s+/).slice(0, 2);
  return parts.map((part) => part.charAt(0).toUpperCase()).join('') || '?';
}

export function Avatar({ name, src, size = 'md', status, className }: AvatarProps) {
  return (
    <span
      className={[styles.avatar, styles[`avatar-${size}`], className].filter(Boolean).join(' ')}
      role="img"
      aria-label={name}
    >
      {src ? (
        // eslint-disable-next-line @next/next/no-img-element
        <img src={src} alt="" className={styles.avatarImage} />
      ) : (
        <span aria-hidden="true">{initials(name)}</span>
      )}
      {status ? (
        <span
          className={[styles.avatarStatus, styles[`avatarStatus-${status}`]].join(' ')}
          aria-hidden="true"
        />
      ) : null}
    </span>
  );
}

/* -------------------------------------------------------------------------- */
/* DataTable                                                                   */
/* -------------------------------------------------------------------------- */

export type SortDirection = 'asc' | 'desc';

export interface SortState {
  readonly key: string;
  readonly direction: SortDirection;
}

export interface Column<T> {
  readonly key: string;
  readonly header: string;
  readonly render: (row: T) => ReactNode;
  readonly sortable?: boolean;
  readonly align?: 'start' | 'center' | 'end';
  readonly width?: string;
  /** Renders the cell as a link to the record (keyboard reachable by design). */
  readonly link?: (row: T) => string;
}

export interface DataTableProps<T> {
  /** Accessible table name — required, rendered as `<caption>`. */
  readonly caption: string;
  readonly columns: readonly Column<T>[];
  readonly rows: readonly T[];
  readonly rowKey: (row: T) => string;
  readonly sort?: SortState;
  readonly onSortChange?: (sort: SortState) => void;
  readonly loading?: boolean;
  readonly skeletonRows?: number;
  /** Rendered inside the table when there is no data (never a blank table). */
  readonly emptyState?: ReactNode;
  readonly rowActions?: (row: T) => ReactNode;
  /** Adds a checkbox column; the table only reports clicks, the owner keeps the selection. */
  readonly selection?: {
    readonly selected: ReadonlySet<string>;
    readonly onToggle: (key: string) => void;
    readonly onToggleAll: (keys: readonly string[], checked: boolean) => void;
    readonly rowLabel: (row: T) => string;
    readonly allLabel: string;
  };
  readonly dense?: boolean;
  readonly className?: string;
}

export function DataTable<T>({
  caption,
  columns,
  rows,
  rowKey,
  sort,
  onSortChange,
  loading = false,
  skeletonRows = 6,
  emptyState,
  rowActions,
  selection,
  dense = false,
  className,
}: DataTableProps<T>) {
  const t = useTranslations('common');

  const toggleSort = (key: string): void => {
    if (!onSortChange) return;
    const direction: SortDirection = sort?.key === key && sort.direction === 'asc' ? 'desc' : 'asc';
    onSortChange({ key, direction });
  };

  const showEmpty = !loading && rows.length === 0;

  return (
    <div className={[styles.tableWrapper, className].filter(Boolean).join(' ')}>
      <table className={[styles.table, dense ? styles.tableDense : ''].filter(Boolean).join(' ')}>
        <caption className={styles.visuallyHidden}>{caption}</caption>
        <thead>
          <tr>
            {selection ? (
              <th scope="col" className={styles.th} style={{ width: '2.5rem' }}>
                <input
                  type="checkbox"
                  aria-label={selection.allLabel}
                  checked={rows.length > 0 && rows.every((row) => selection.selected.has(rowKey(row)))}
                  onChange={(event) => selection.onToggleAll(rows.map(rowKey), event.target.checked)}
                />
              </th>
            ) : null}
            {columns.map((column) => {
              const isSorted = sort?.key === column.key;
              const ariaSort = isSorted
                ? sort.direction === 'asc'
                  ? 'ascending'
                  : 'descending'
                : undefined;
              return (
                <th
                  key={column.key}
                  scope="col"
                  aria-sort={column.sortable ? (ariaSort ?? 'none') : undefined}
                  style={column.width ? { width: column.width } : undefined}
                  className={[styles.th, styles[`align-${column.align ?? 'start'}`]]
                    .filter(Boolean)
                    .join(' ')}
                >
                  {column.sortable && onSortChange ? (
                    <button
                      type="button"
                      className={styles.sortButton}
                      onClick={() => toggleSort(column.key)}
                      title={
                        isSorted && sort.direction === 'asc'
                          ? t('labels.sortedAscending')
                          : isSorted
                            ? t('labels.sortedDescending')
                            : t('labels.sortBy', { column: column.header })
                      }
                    >
                      <span>{column.header}</span>
                      <span className={styles.sortIcon} aria-hidden="true">
                        <Icon
                          name="arrow-up-down"
                          size={14}
                          className={isSorted ? styles.sortIconActive : undefined}
                        />
                      </span>
                    </button>
                  ) : (
                    column.header
                  )}
                </th>
              );
            })}
            {rowActions ? (
              <th scope="col" className={[styles.th, styles.alignEnd].join(' ')}>
                {t('labels.actions')}
              </th>
            ) : null}
          </tr>
        </thead>
        <tbody>
          {loading ? (
            <tr>
              <td colSpan={columns.length + (rowActions ? 1 : 0) + (selection ? 1 : 0)} className={styles.stateCell}>
                <SkeletonTable rows={skeletonRows} columns={columns.length} />
              </td>
            </tr>
          ) : null}
          {showEmpty ? (
            <tr>
              <td colSpan={columns.length + (rowActions ? 1 : 0) + (selection ? 1 : 0)} className={styles.stateCell}>
                {emptyState}
              </td>
            </tr>
          ) : null}
          {!loading
            ? rows.map((row) => (
                <tr key={rowKey(row)} className={styles.tr}>
                  {selection ? (
                    <td className={styles.td}>
                      <input
                        type="checkbox"
                        aria-label={selection.rowLabel(row)}
                        checked={selection.selected.has(rowKey(row))}
                        onChange={() => selection.onToggle(rowKey(row))}
                      />
                    </td>
                  ) : null}
                  {columns.map((column) => {
                    const content = column.render(row);
                    return (
                      <td
                        key={column.key}
                        className={[styles.td, styles[`align-${column.align ?? 'start'}`]]
                          .filter(Boolean)
                          .join(' ')}
                      >
                        {column.link ? (
                          <Link href={column.link(row)} className={styles.cellLink}>
                            {content}
                          </Link>
                        ) : (
                          content
                        )}
                      </td>
                    );
                  })}
                  {rowActions ? (
                    <td className={[styles.td, styles.alignEnd, styles.actionsCell].join(' ')}>
                      {rowActions(row)}
                    </td>
                  ) : null}
                </tr>
              ))
            : null}
        </tbody>
      </table>
    </div>
  );
}

/* -------------------------------------------------------------------------- */
/* Pagination                                                                  */
/* -------------------------------------------------------------------------- */

export interface PaginationProps {
  /** Zero-based page index (matches the API envelope). */
  readonly page: number;
  readonly totalPages: number;
  readonly totalItems?: number;
  readonly onPageChange: (page: number) => void;
  readonly disabled?: boolean;
  readonly className?: string;
}

function pageWindow(page: number, totalPages: number): Array<number | 'gap'> {
  const pages = new Set<number>([0, totalPages - 1, page - 1, page, page + 1]);
  const list = Array.from(pages)
    .filter((value) => value >= 0 && value < totalPages)
    .sort((a, b) => a - b);
  const result: Array<number | 'gap'> = [];
  let previous = -1;
  for (const value of list) {
    if (previous >= 0 && value - previous > 1) result.push('gap');
    result.push(value);
    previous = value;
  }
  return result;
}

export function Pagination({
  page,
  totalPages,
  totalItems,
  onPageChange,
  disabled = false,
  className,
}: PaginationProps) {
  const t = useTranslations('common');
  const safeTotal = Math.max(1, totalPages);
  const current = Math.min(Math.max(0, page), safeTotal - 1);

  return (
    <nav
      className={[styles.pagination, className].filter(Boolean).join(' ')}
      aria-label={t('labels.page')}
    >
      <p className={styles.paginationSummary}>
        {totalItems !== undefined ? `${t('labels.results', { count: totalItems })} · ` : ''}
        {t('labels.page')} {current + 1} {t('labels.of')} {safeTotal}
      </p>
      <div className={styles.paginationControls}>
        <button
          type="button"
          className={styles.pageButton}
          onClick={() => onPageChange(current - 1)}
          disabled={disabled || current === 0}
          aria-label={t('actions.previous')}
        >
          <Icon name="chevron-left" size={16} />
        </button>
        {pageWindow(current, safeTotal).map((entry, index) =>
          entry === 'gap' ? (
            <span key={`gap-${index}`} className={styles.pageGap} aria-hidden="true">
              …
            </span>
          ) : (
            <button
              key={entry}
              type="button"
              className={[styles.pageButton, entry === current ? styles.pageButtonActive : '']
                .filter(Boolean)
                .join(' ')}
              onClick={() => onPageChange(entry)}
              disabled={disabled}
              aria-current={entry === current ? 'page' : undefined}
              aria-label={`${t('labels.page')} ${entry + 1}`}
            >
              {entry + 1}
            </button>
          ),
        )}
        <button
          type="button"
          className={styles.pageButton}
          onClick={() => onPageChange(current + 1)}
          disabled={disabled || current >= safeTotal - 1}
          aria-label={t('actions.next')}
        >
          <Icon name="chevron-right" size={16} />
        </button>
      </div>
    </nav>
  );
}

/* -------------------------------------------------------------------------- */
/* Breadcrumbs                                                                 */
/* -------------------------------------------------------------------------- */

export interface BreadcrumbItem {
  readonly label: string;
  readonly href?: string;
}

export function Breadcrumbs({
  items,
  className,
}: {
  items: readonly BreadcrumbItem[];
  className?: string;
}) {
  const t = useTranslations('nav');
  if (items.length === 0) return null;
  return (
    <nav
      className={[styles.breadcrumbs, className].filter(Boolean).join(' ')}
      aria-label={t('breadcrumb')}
    >
      <ol className={styles.breadcrumbList}>
        {items.map((item, index) => {
          const isLast = index === items.length - 1;
          return (
            <li key={`${item.label}-${index}`} className={styles.breadcrumbItem}>
              {item.href && !isLast ? (
                <Link href={item.href} className={styles.breadcrumbLink}>
                  {item.label}
                </Link>
              ) : (
                <span
                  aria-current={isLast ? 'page' : undefined}
                  className={styles.breadcrumbCurrent}
                >
                  {item.label}
                </span>
              )}
              {!isLast ? (
                <span className={styles.breadcrumbSeparator} aria-hidden="true">
                  /
                </span>
              ) : null}
            </li>
          );
        })}
      </ol>
    </nav>
  );
}
