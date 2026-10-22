'use client';

import Link from 'next/link';
import { useMemo, useState } from 'react';
import { PageHeader, PageSection, ResourceView } from '@/components/app/resource-view';
import { Badge, Card, CardBody, DataTable, EmptyState, type Column } from '@/components/ui';
import { Button } from '@/components/ui/button';
import { InlineAlert, SkeletonTable } from '@/components/ui/feedback';
import { SearchInput } from '@/components/ui/form';
import { ConfirmDialog } from '@/components/ui/overlay';
import { useToast } from '@/components/ui/toast';
import { formatDateTime } from '@/i18n/format';
import { useLocale, useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { toApiError } from '@/lib/api/errors';
import { useApiResource } from '@/lib/api/hooks';
import { useSession } from '@/lib/auth/session-context';
import type { MandantRule } from '@/lib/api/types';
import { filterMandantRules, isLearned } from './mandant-rules-model';
import styles from './mandant-rules.module.css';

const NO_RULES: readonly MandantRule[] = [];

/**
 * The rules the platform learned for assigning vouchers to clients (Kanzlei edition, §5.2).
 *
 * <p><strong>Why the screen exists.</strong> When a person assigns a voucher by hand, the platform remembers the
 * sender's e-mail, the supplier's name and the IBAN. From then on, similar vouchers are assigned without anybody
 * clicking - which is the point of the module, and also its risk: a single wrong rule keeps producing wrong
 * assignments. The table therefore shows every rule with its signal, its client, how often it fired and when it
 * was last used, and the delete button removes one rule in one click.</p>
 *
 * <p><strong>What deletion means and does not mean.</strong> Deleting a rule stops future automatic assignments
 * from that signal. Vouchers that are already assigned stay as they are: deleting knowledge is not the same as
 * moving documents, and only a person may re-assign (§5.2).</p>
 *
 * <p>Reading needs {@code settings.view}; deleting needs {@code settings.manage_rules}. The server decides; the
 * screen only avoids showing a button that would be refused.</p>
 */
export default function MandantRulesPage() {
  const t = useTranslations('mandantRules');
  const tNav = useTranslations('nav');
  const tErrors = useTranslations('errors');
  const locale = useLocale();
  const toast = useToast();
  const session = useSession();

  const permissions = session?.permissions;
  const canView = permissions === undefined || permissions.includes('settings.view');
  const canEdit = permissions?.includes('settings.manage_rules') === true;
  const showReadOnly = permissions !== undefined && canView && !canEdit;

  const { state, reload, isRefreshing } = useApiResource(
    (signal) => api.settings.mandantRules.list(signal),
    [],
    // A delete must not blank the table while the new state is fetched.
    { skip: !canView, keepPreviousData: true },
  );

  const [query, setQuery] = useState('');
  const [deleting, setDeleting] = useState<MandantRule | null>(null);
  const [deletePending, setDeletePending] = useState(false);

  const signalLabel = useMemo(
    () => (rule: MandantRule) => t(`signal.${rule.signalTyp}` as 'signal.IBAN'),
    [t],
  );

  const rules = state.status === 'ready' ? state.data.items : NO_RULES;
  const filtered = useMemo(
    () => filterMandantRules(rules, query, signalLabel),
    [rules, query, signalLabel],
  );

  const remove = async (): Promise<void> => {
    if (!deleting) return;
    setDeletePending(true);
    try {
      await api.settings.mandantRules.remove(deleting.id);
      toast.success(t('delete.done'));
      setDeleting(null);
      reload();
    } catch (error) {
      toast.error(tErrors(toApiError(error).kind));
    } finally {
      setDeletePending(false);
    }
  };

  const columns: readonly Column<MandantRule>[] = useMemo(
    () => [
      {
        key: 'signal',
        header: t('table.signal'),
        render: (rule) => (
          <Badge tone="neutral" variant="outline">
            {signalLabel(rule)}
          </Badge>
        ),
      },
      {
        key: 'muster',
        header: t('table.muster'),
        render: (rule) => (
          <code className={styles.muster} title={rule.muster}>
            {rule.muster}
          </code>
        ),
      },
      {
        key: 'mandant',
        header: t('table.mandant'),
        render: (rule) => rule.mandantName ?? '—',
      },
      {
        key: 'treffer',
        header: t('table.hits'),
        render: (rule) => <span className={styles.number}>{rule.treffer}</span>,
      },
      {
        key: 'letzteNutzung',
        header: t('table.lastUsed'),
        render: (rule) =>
          rule.letzteNutzung ? formatDateTime(rule.letzteNutzung, locale) : t('table.never'),
      },
      {
        key: 'quelle',
        header: t('table.source'),
        render: (rule) => (
          <Badge tone={isLearned(rule) ? 'accent' : 'info'} variant="subtle">
            {t(`source.${rule.quelle}` as 'source.MANUELL')}
          </Badge>
        ),
      },
    ],
    [t, signalLabel, locale],
  );

  if (!canView) {
    return (
      <>
        <PageHeader title={tNav('items.mandantRules')} subtitle={t('subtitle')} />
        <InlineAlert tone="warning" title={t('forbidden.title')}>
          {t('forbidden.description')}
        </InlineAlert>
        <p>
          <Link href="/settings">{t('forbidden.back')}</Link>
        </p>
      </>
    );
  }

  return (
    <>
      <PageHeader title={tNav('items.mandantRules')} subtitle={t('subtitle')} />

      <InlineAlert tone="info" title={t('intro.title')}>
        {t('intro.text')}
      </InlineAlert>
      <InlineAlert tone="warning" title={t('deleteHint.title')}>
        {t('deleteHint.text')}
      </InlineAlert>
      {showReadOnly ? <InlineAlert tone="neutral">{t('readOnly')}</InlineAlert> : null}

      <PageSection title={t('table.title')} description={t('table.description')}>
        <ResourceView
          state={state}
          onRetry={reload}
          isRefreshing={isRefreshing}
          isEmpty={(data) => data.items.length === 0}
          emptyState={
            <EmptyState
              icon="users"
              title={t('empty.title')}
              description={t('empty.description')}
            />
          }
          skeleton={<SkeletonTable rows={4} columns={6} />}
        >
          {(data) => (
            <>
              <Card>
                <CardBody>
                  <div className={styles.filters}>
                    <SearchInput
                      id="mandant-rules-search"
                      label={t('search.label')}
                      value={query}
                      onChange={(event) => setQuery(event.target.value)}
                    />
                    <Button variant="ghost" onClick={() => setQuery('')}>
                      {t('search.clear')}
                    </Button>
                  </div>
                  <p className={styles.shown} aria-live="polite">
                    {t('summary', { shown: filtered.length, total: data.count })}
                  </p>
                  <p className={styles.hint}>{t('empty.hint')}</p>
                </CardBody>
              </Card>

              {filtered.length === 0 ? (
                <InlineAlert tone="info" title={t('search.noMatches')}>
                  {t('search.noMatchesHint')}
                </InlineAlert>
              ) : (
                <DataTable
                  caption={t('table.title')}
                  columns={columns}
                  rows={filtered}
                  rowKey={(rule) => rule.id}
                  rowActions={(rule) =>
                    canEdit ? (
                      <Button variant="ghost" size="sm" onClick={() => setDeleting(rule)}>
                        {t('delete.confirm')}
                      </Button>
                    ) : null
                  }
                />
              )}
            </>
          )}
        </ResourceView>
      </PageSection>

      <ConfirmDialog
        open={deleting !== null}
        title={t('delete.title')}
        description={
          deleting
            ? `${signalLabel(deleting)} · ${deleting.muster} — ${t('delete.description')}`
            : t('delete.description')
        }
        confirmLabel={t('delete.confirm')}
        loading={deletePending}
        onConfirm={() => void remove()}
        onCancel={() => setDeleting(null)}
      />
    </>
  );
}
