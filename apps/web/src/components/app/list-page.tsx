'use client';

import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import { Button } from '@/components/ui/button';
import { DataTable, EmptyState, Pagination, SearchInput, Select } from '@/components/ui';
import type { Column } from '@/components/ui';
import { ResourceView, PageHeader } from '@/components/app/resource-view';
import {
  CreateRecordDialog,
  Toolbar,
  type FormFieldDefinition,
} from '@/components/app/create-record-dialog';
import { useToast } from '@/components/ui/toast';
import { SkeletonTable } from '@/components/ui/feedback';
import { useGlobalTranslations, useTranslations } from '@/i18n/provider';
import type { MessageValues } from '@/i18n/icu';
import { useApiResource } from '@/lib/api/hooks';
import type { ListParams, Page } from '@/lib/api/client';
import { getScreen, type ScreenId } from '@/navigation/screens';
import type { IconName } from '@/components/icons';

export interface ListFilterDefinition {
  readonly key: string;
  readonly label: string;
  readonly options: readonly { readonly value: string; readonly label: string }[];
}

export interface ListPageProps<T> {
  readonly screenId: ScreenId;
  /** Screen namespace; keys follow the shared screen contract (`subtitle`, `empty.title`, …). */
  readonly namespace: string;
  readonly load: (params: ListParams, signal: AbortSignal) => Promise<Page<T>>;
  readonly columns: readonly Column<T>[];
  /** Accessible table caption; defaults to the screen title. */
  readonly caption?: string;
  readonly filters?: readonly ListFilterDefinition[];
  readonly searchable?: boolean;
  readonly sortableKeys?: readonly string[];
  readonly pageSize?: number;
  readonly emptyIcon?: IconName;
  readonly headerActions?: ReactNode;
  readonly createFields?: readonly FormFieldDefinition[];
  readonly onCreate?: (values: Record<string, string>) => Promise<unknown>;
  readonly createTitle?: string;
  /** Renders extra content above the table (e.g. notices). */
  readonly notice?: ReactNode;
  readonly rowActions?: (row: T, context: { readonly reload: () => void }) => ReactNode;
  /** Adds checkboxes; the actions appear above the table while something is selected. */
  readonly selectable?: {
    readonly allLabel: string;
    readonly rowLabel: (row: T) => string;
    readonly actions: (context: {
      readonly ids: readonly string[];
      readonly clear: () => void;
      readonly reload: () => void;
    }) => ReactNode;
  };
}

/**
 * Shared list screen scaffold.
 *
 * Every list screen in the product uses this component, which guarantees the
 * four mandatory states (docs/task.md §29), server-side pagination, sorting,
 * filtering and an accessible table without duplicating logic per page.
 *
 * All copy is read from the screen namespace following a fixed key contract:
 * `subtitle`, `searchPlaceholder`, `action.new`, `empty.title`,
 * `empty.description`, `empty.action`, `filter.*`, `column.*`.
 */
export function ListPage<T>({
  screenId,
  namespace,
  load,
  columns,
  caption,
  filters = [],
  searchable = true,
  sortableKeys,
  pageSize = 20,
  emptyIcon = 'inbox',
  headerActions,
  createFields,
  onCreate,
  createTitle,
  notice,
  rowActions,
  selectable,
}: ListPageProps<T>) {
  const screen = getScreen(screenId);
  const tNav = useTranslations('nav');
  const tCommon = useTranslations('common');
  const tGlobal = useGlobalTranslations();
  const toast = useToast();

  const [page, setPage] = useState(0);
  const [query, setQuery] = useState('');
  const [filterValues, setFilterValues] = useState<Record<string, string>>({});
  const [sort, setSort] = useState<{ key: string; direction: 'asc' | 'desc' } | undefined>();
  const [dialogOpen, setDialogOpen] = useState(false);

  /** Dynamic keys from the shared screen contract; missing keys degrade to ''. */
  const tr = useCallback(
    (key: string, values?: MessageValues): string =>
      (tGlobal as unknown as (ns: string, key: string, values?: MessageValues) => string)(
        namespace,
        key,
        values,
      ),
    [tGlobal, namespace],
  );

  const params = useMemo<ListParams>(() => {
    const active: Record<string, string | number> = {};
    for (const [key, value] of Object.entries(filterValues)) {
      if (value !== '') active[key] = value;
    }
    return {
      page,
      size: pageSize,
      ...(query.trim() !== '' ? { q: query.trim() } : {}),
      ...(sort ? { sort: `${sort.key},${sort.direction}` } : {}),
      ...active,
    };
  }, [page, pageSize, query, sort, filterValues]);

  const { state, reload, isRefreshing } = useApiResource<Page<T>>(
    (signal) => load(params, signal),
    [params],
    { keepPreviousData: true },
  );

  const [selected, setSelected] = useState<ReadonlySet<string>>(new Set());
  // What is on screen changes with the page, the search and the filters: a selection must not reach rows nobody sees.
  useEffect(() => {
    setSelected(new Set());
  }, [params]);

  const title = tNav(screen.titleKey);
  const canCreate = Boolean(createFields && onCreate);

  const handleCreate = async (values: Record<string, string>): Promise<void> => {
    if (!onCreate) return;
    await onCreate(values);
    toast.success(tr('action.new'), tCommon('labels.new'));
    reload();
  };

  return (
    <>
      <PageHeader
        title={title}
        subtitle={tr('subtitle')}
        actions={
          <>
            {headerActions}
            {canCreate ? (
              <Button variant="primary" iconLeft="plus" onClick={() => setDialogOpen(true)}>
                {tr('action.new')}
              </Button>
            ) : null}
          </>
        }
      />

      {notice}

      <Toolbar>
        {searchable ? (
          <SearchInput
            label={tCommon('actions.search')}
            placeholder={tr('searchPlaceholder')}
            value={query}
            onChange={(event) => {
              setQuery(event.target.value);
              setPage(0);
            }}
          />
        ) : null}
        {filters.map((filter) => (
          <Select
            key={filter.key}
            label={filter.label}
            selectSize="sm"
            placeholder={tCommon('labels.all')}
            options={[...filter.options]}
            value={filterValues[filter.key] ?? ''}
            onChange={(event) => {
              setFilterValues((current) => ({ ...current, [filter.key]: event.target.value }));
              setPage(0);
            }}
          />
        ))}
        {query !== '' || Object.keys(filterValues).length > 0 ? (
          <Button
            variant="ghost"
            iconLeft="refresh"
            onClick={() => {
              setQuery('');
              setFilterValues({});
              setPage(0);
            }}
          >
            {tCommon('actions.reset')}
          </Button>
        ) : null}
      </Toolbar>

      <ResourceView
        state={state}
        onRetry={reload}
        isRefreshing={isRefreshing}
        skeleton={<SkeletonTable rows={6} columns={Math.max(4, columns.length)} />}
        isEmpty={(data) => data.items.length === 0}
        emptyState={
          <EmptyState
            icon={emptyIcon}
            title={tr('empty.title')}
            description={tr('empty.description')}
            actions={
              canCreate ? (
                <Button variant="primary" iconLeft="plus" onClick={() => setDialogOpen(true)}>
                  {tr('empty.action')}
                </Button>
              ) : (
                <Button variant="secondary" iconLeft="refresh" onClick={reload}>
                  {tCommon('actions.refresh')}
                </Button>
              )
            }
          />
        }
      >
        {(data) => (
          <>
            {selectable && selected.size > 0 ? (
              <div style={{ marginBottom: '0.75rem' }}>
                {selectable.actions({
                  ids: [...selected],
                  clear: () => setSelected(new Set()),
                  reload,
                })}
              </div>
            ) : null}
            <DataTable
              caption={caption ?? title}
              columns={columns}
              rows={data.items}
              rowKey={(row) => String((row as { id?: string }).id ?? JSON.stringify(row))}
              sort={sort}
              onSortChange={
                sortableKeys
                  ? (next) => {
                      setSort(next);
                      setPage(0);
                    }
                  : undefined
              }
              rowActions={rowActions ? (row) => rowActions(row, { reload }) : undefined}
              selection={
                selectable
                  ? {
                      selected,
                      allLabel: selectable.allLabel,
                      rowLabel: selectable.rowLabel,
                      onToggle: (key) =>
                        setSelected((current) => {
                          const next = new Set(current);
                          if (next.has(key)) next.delete(key);
                          else next.add(key);
                          return next;
                        }),
                      onToggleAll: (keys, checked) =>
                        setSelected((current) => {
                          const next = new Set(current);
                          for (const key of keys) {
                            if (checked) next.add(key);
                            else next.delete(key);
                          }
                          return next;
                        }),
                    }
                  : undefined
              }
            />
            <Pagination
              page={data.page}
              totalPages={data.totalPages}
              totalItems={data.totalItems}
              onPageChange={setPage}
              disabled={isRefreshing}
            />
          </>
        )}
      </ResourceView>

      {canCreate && createFields ? (
        <CreateRecordDialog
          open={dialogOpen}
          onClose={() => setDialogOpen(false)}
          title={createTitle ?? tr('action.new')}
          submitLabel={tCommon('actions.create')}
          fields={createFields}
          onSubmit={handleCreate}
          successMessage={`${tr('action.new')} · ${tCommon('labels.new')}`}
        />
      ) : null}
    </>
  );
}
