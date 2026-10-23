'use client';

import Link from 'next/link';
import { useMemo, useState } from 'react';
import { PageHeader, PageSection, ResourceView } from '@/components/app/resource-view';
import { Badge, DataTable, EmptyState, type Column } from '@/components/ui';
import { Button } from '@/components/ui/button';
import { buttonClass } from '@/components/ui/button-class';
import { SkeletonTable } from '@/components/ui/feedback';
import { SearchInput, Switch } from '@/components/ui/form';
import { formatDate, formatNumber } from '@/i18n/format';
import { useLocale, useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { useApiResource } from '@/lib/api/hooks';
import type { MandantHealth, MandantSummary } from '@/lib/api/types';
import styles from './mandanten.module.css';

/**
 * Where a client's state is painted (Kanzlei edition, §5.5). The colour is never the only carrier of the
 * meaning - the label says the same thing, so a person who cannot distinguish the colours still reads it.
 */
const HEALTH_TONE: Readonly<Record<MandantHealth, 'success' | 'warning' | 'danger'>> = {
  OK: 'success',
  ACTION: 'warning',
  RISK: 'danger',
};

/**
 * The clients of the Kanzlei (Kanzlei edition, §5.5, §7).
 *
 * <p>One row per client, and the row answers the morning question: is anything wrong here. The counters come
 * from the server in one request - the screen never sums up documents itself, because a second calculation
 * would be a second truth the moment the first one changes.</p>
 *
 * <p>The empty state is the first-run path of §6.2: a cabinet without clients is not an error, it is the
 * first minute of the product, and it says what to do ("Legen Sie Ihren ersten Mandanten an") instead of
 * showing an empty table.</p>
 */
export default function MandantenPage() {
  const t = useTranslations('mandanten');
  const tNav = useTranslations('nav');
  const tCommon = useTranslations('common');
  const locale = useLocale();

  const [query, setQuery] = useState('');
  const [onlyProblems, setOnlyProblems] = useState(false);

  const { state, reload, isRefreshing } = useApiResource(
    (signal) =>
      api.mandanten.list({ q: query.trim() === '' ? undefined : query.trim(), onlyProblems }, signal),
    [query, onlyProblems],
  );

  const columns: readonly Column<MandantSummary>[] = useMemo(
    () => [
      {
        key: 'mandant',
        header: t('column.mandant'),
        link: (row) => `/mandanten/${row.id}`,
        render: (row) => row.displayName,
      },
      {
        key: 'health',
        header: t('column.health'),
        render: (row) => (
          <span title={t(`health.${row.health}.hint` as 'health.OK.hint')}>
            <Badge tone={HEALTH_TONE[row.health]}>{t(`health.${row.health}` as 'health.OK')}</Badge>
          </span>
        ),
      },
      {
        key: 'documents',
        header: t('column.documents'),
        align: 'end',
        render: (row) => formatNumber(row.documentsTotal, locale),
      },
      {
        key: 'offen',
        header: t('column.offen'),
        align: 'end',
        render: (row) => formatNumber(row.offen, locale),
      },
      {
        key: 'zuPruefen',
        header: t('column.zuPruefen'),
        align: 'end',
        render: (row) => formatNumber(row.zuPruefen, locale),
      },
      {
        key: 'deadlines',
        header: t('column.deadlines'),
        align: 'end',
        render: (row) => formatNumber(row.deadlinesOpen, locale),
      },
      {
        key: 'lastActivity',
        header: t('column.lastActivity'),
        render: (row) => (row.lastActivityAt ? formatDate(row.lastActivityAt, locale) : tCommon('labels.none')),
      },
    ],
    [t, tCommon, locale],
  );

  const filtered = query.trim() !== '' || onlyProblems;

  return (
    <>
      <PageHeader title={tNav('items.mandanten')} subtitle={t('subtitle')} />

      <PageSection>
        <div className={styles.toolbar}>
          <SearchInput
            label={tCommon('actions.search')}
            placeholder={t('searchPlaceholder')}
            value={query}
            onChange={(event) => setQuery(event.target.value)}
          />
          <Switch
            label={t('filter.onlyProblems')}
            description={t('filter.onlyProblemsHint')}
            checked={onlyProblems}
            onChange={setOnlyProblems}
          />
        </div>

        <ResourceView
          state={state}
          onRetry={reload}
          isRefreshing={isRefreshing}
          isEmpty={(rows) => rows.length === 0}
          skeleton={<SkeletonTable rows={6} columns={7} />}
          emptyState={
            filtered ? (
              <EmptyState
                icon="users"
                title={t('empty.filtered.title')}
                description={t('empty.filtered.description')}
                actions={
                  <Button
                    variant="secondary"
                    iconLeft="refresh"
                    onClick={() => {
                      setQuery('');
                      setOnlyProblems(false);
                    }}
                  >
                    {t('empty.filtered.action')}
                  </Button>
                }
              />
            ) : (
              <EmptyState
                icon="users"
                title={t('empty.title')}
                description={t('empty.description')}
                actions={
                  <Link href="/contacts" className={buttonClass({ variant: 'primary' })}>
                    {t('empty.action')}
                  </Link>
                }
              />
            )
          }
        >
          {(rows) => (
            <DataTable
              caption={tNav('items.mandanten')}
              columns={columns}
              rows={rows}
              rowKey={(row) => row.id}
            />
          )}
        </ResourceView>
      </PageSection>
    </>
  );
}
