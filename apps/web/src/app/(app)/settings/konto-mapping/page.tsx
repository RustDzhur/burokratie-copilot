'use client';

import Link from 'next/link';
import { useEffect, useMemo, useState, type ChangeEvent, type FormEvent } from 'react';
import { PageHeader, PageSection, ResourceView } from '@/components/app/resource-view';
import { Card, CardBody, DataTable, EmptyState, type Column } from '@/components/ui';
import { Button } from '@/components/ui/button';
import { InlineAlert, SkeletonTable } from '@/components/ui/feedback';
import { Field, Input, SearchInput, Select } from '@/components/ui/form';
import { ConfirmDialog, Dialog } from '@/components/ui/overlay';
import { useToast } from '@/components/ui/toast';
import { useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { toApiError } from '@/lib/api/errors';
import { useApiResource } from '@/lib/api/hooks';
import { useSession } from '@/lib/auth/session-context';
import type {
  ContactSummary,
  KontoMapping,
  KontoMappingImportReport,
  KontoMappingInput,
} from '@/lib/api/types';
import { filterKontoMappings, kontoMappingLabel } from './konto-mapping-model';
import styles from './konto-mapping.module.css';

const NO_MAPPINGS: readonly KontoMapping[] = [];
const NO_MANDANTS: readonly ContactSummary[] = [];
const NO_CATEGORIES: readonly string[] = [];

/** The full state of the edit dialog. An empty mandant/category means "for everyone". */
interface FormState {
  readonly id: string | null;
  readonly mandantId: string;
  readonly supplier: string;
  readonly category: string;
  readonly sollkonto: string;
  readonly habenkonto: string;
  readonly steuerschluessel: string;
}

const EMPTY_FORM: FormState = {
  id: null,
  mandantId: '',
  supplier: '',
  category: '',
  sollkonto: '',
  habenkonto: '',
  steuerschluessel: '',
};

/**
 * The account mapping of the DATEV export (Kanzlei edition, §5.6, §8.4).
 *
 * <p><strong>What this screen prevents.</strong> The export reads {@code konto_mapping} to decide the accounts
 * of a voucher. With no row, every voucher is skipped with "Kein Konto-Mapping" and the file is empty — the
 * feature looks broken while working exactly as specified. The screen therefore says that consequence above
 * the table instead of leaving it in a manual, and the empty state repeats it next to the first action.</p>
 *
 * <p><strong>What the rows mean.</strong> Client, supplier and category are optional dimensions: the export
 * picks the most specific matching row. The screen explains the order of precedence in words, because "why
 * did this voucher take those accounts" is the question a bookkeeper will ask.</p>
 *
 * <p>Reading needs {@code settings.view}; every change needs {@code settings.manage_rules}. The server
 * decides; the screen only avoids showing buttons that would be refused.</p>
 */
export default function KontoMappingPage() {
  const t = useTranslations('kontoMapping');
  const tNav = useTranslations('nav');
  const tErrors = useTranslations('errors');
  const toast = useToast();
  const session = useSession();

  const permissions = session?.permissions;
  const canView = permissions === undefined || permissions.includes('settings.view');
  const canEdit = permissions?.includes('settings.manage_rules') === true;
  const showReadOnly = permissions !== undefined && canView && !canEdit;

  const { state, reload, isRefreshing } = useApiResource(
    (signal) => api.settings.kontoMapping.list(undefined, signal),
    [],
    // A save must not blank the table: the rows stay while the new state is fetched.
    { skip: !canView, keepPreviousData: true },
  );

  const [query, setQuery] = useState('');
  const [form, setForm] = useState<FormState | null>(null);
  const [formError, setFormError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [deleting, setDeleting] = useState<KontoMapping | null>(null);
  const [deletePending, setDeletePending] = useState(false);

  const [importOpen, setImportOpen] = useState(false);
  const [importFile, setImportFile] = useState<File | null>(null);
  const [importing, setImporting] = useState(false);
  const [report, setReport] = useState<KontoMappingImportReport | null>(null);
  const [importError, setImportError] = useState<string | null>(null);

  const [mandants, setMandants] = useState<readonly ContactSummary[]>(NO_MANDANTS);
  const [mandantsUnavailable, setMandantsUnavailable] = useState(false);

  // The client list is optional: right to change the mapping does not have to include the right to read
  // contacts. A failed lookup leaves "Alle Mandanten" as the only choice and says so, instead of taking the
  // screen down.
  useEffect(() => {
    if (!canEdit) return undefined;
    const controller = new AbortController();
    api.contacts
      .list({ size: 250, sort: 'name,asc' }, controller.signal)
      .then((page) => setMandants(page.items))
      .catch(() => {
        if (!controller.signal.aborted) setMandantsUnavailable(true);
      });
    return () => controller.abort();
  }, [canEdit]);

  const mappings = state.status === 'ready' ? state.data.items : NO_MAPPINGS;
  const categories = state.status === 'ready' ? state.data.categories : NO_CATEGORIES;
  const filtered = useMemo(() => filterKontoMappings(mappings, query), [mappings, query]);

  /** The client options of the dialog; the current row's client stays selectable even if it is not listed. */
  const mandantOptions = useMemo(() => {
    const options = mandants.map((mandant) => ({ value: mandant.id, label: mandant.name }));
    if (form?.mandantId && !options.some((option) => option.value === form.mandantId)) {
      const current = mappings.find((mapping) => mapping.mandantId === form.mandantId);
      options.unshift({
        value: form.mandantId,
        label: current?.mandantName ?? form.mandantId,
      });
    }
    return options;
  }, [mandants, form?.mandantId, mappings]);

  /** The category options; a row's category that the taxonomy no longer lists stays selectable. */
  const categoryOptions = useMemo(() => {
    const options = categories.map((category) => ({ value: category, label: category }));
    if (form?.category && !options.some((option) => option.value === form.category)) {
      options.unshift({ value: form.category, label: form.category });
    }
    return options;
  }, [categories, form?.category]);

  const openCreate = (): void => {
    setFormError(null);
    setForm({ ...EMPTY_FORM });
  };

  const openEdit = (mapping: KontoMapping): void => {
    setFormError(null);
    setForm({
      id: mapping.id,
      mandantId: mapping.mandantId ?? '',
      supplier: mapping.supplier ?? '',
      category: mapping.category ?? '',
      sollkonto: mapping.sollkonto,
      habenkonto: mapping.habenkonto,
      steuerschluessel: mapping.steuerschluessel ?? '',
    });
  };

  const save = async (event: FormEvent<HTMLFormElement>): Promise<void> => {
    event.preventDefault();
    if (!form) return;
    const sollkonto = form.sollkonto.trim();
    const habenkonto = form.habenkonto.trim();
    if (sollkonto === '') {
      setFormError(t('form.sollRequired'));
      return;
    }
    if (habenkonto === '') {
      setFormError(t('form.habenRequired'));
      return;
    }
    if (sollkonto.length > 20 || habenkonto.length > 20) {
      setFormError(t('form.accountTooLong'));
      return;
    }
    const payload: KontoMappingInput = {
      mandantId: form.mandantId === '' ? null : form.mandantId,
      supplier: form.supplier.trim() === '' ? null : form.supplier.trim(),
      category: form.category === '' ? null : form.category,
      sollkonto,
      habenkonto,
      steuerschluessel:
        form.steuerschluessel.trim() === '' ? null : form.steuerschluessel.trim(),
    };
    setFormError(null);
    setSaving(true);
    try {
      if (form.id) await api.settings.kontoMapping.update(form.id, payload);
      else await api.settings.kontoMapping.create(payload);
      toast.success(form.id ? t('form.saved') : t('form.created'));
      setForm(null);
      reload();
    } catch (error) {
      const apiError = toApiError(error);
      // A combination that already exists is a message about the form, not a general failure.
      setFormError(apiError.kind === 'conflict' ? t('form.duplicate') : tErrors(apiError.kind));
    } finally {
      setSaving(false);
    }
  };

  const remove = async (): Promise<void> => {
    if (!deleting) return;
    setDeletePending(true);
    try {
      await api.settings.kontoMapping.remove(deleting.id);
      toast.success(t('form.deleted'));
      setDeleting(null);
      reload();
    } catch (error) {
      toast.error(tErrors(toApiError(error).kind));
    } finally {
      setDeletePending(false);
    }
  };

  const openImport = (): void => {
    setImportFile(null);
    setReport(null);
    setImportError(null);
    setImportOpen(true);
  };

  const submitImport = async (): Promise<void> => {
    if (!importFile) {
      setImportError(t('import.chooseFile'));
      return;
    }
    setImporting(true);
    setImportError(null);
    try {
      const result = await api.settings.kontoMapping.importCsv(importFile);
      setReport(result);
      toast.success(
        t('import.done', {
          created: result.created,
          updated: result.updated,
          skipped: result.skipped.length,
        }),
      );
      reload();
    } catch (error) {
      setImportError(tErrors(toApiError(error).kind));
    } finally {
      setImporting(false);
    }
  };

  const columns: readonly Column<KontoMapping>[] = useMemo(
    () => [
      {
        key: 'mandant',
        header: t('table.mandant'),
        render: (row) => row.mandantName ?? t('table.allMandants'),
      },
      {
        key: 'supplier',
        header: t('table.supplier'),
        render: (row) => row.supplier ?? t('table.anySupplier'),
      },
      {
        key: 'category',
        header: t('table.category'),
        render: (row) => row.category ?? t('table.anyCategory'),
      },
      {
        key: 'sollkonto',
        header: t('table.sollkonto'),
        render: (row) => <span className={styles.account}>{row.sollkonto}</span>,
      },
      {
        key: 'habenkonto',
        header: t('table.habenkonto'),
        render: (row) => <span className={styles.account}>{row.habenkonto}</span>,
      },
      {
        key: 'steuerschluessel',
        header: t('table.taxKey'),
        render: (row) => row.steuerschluessel ?? t('table.none'),
      },
    ],
    [t],
  );

  if (!canView) {
    return (
      <>
        <PageHeader title={tNav('items.kontoMapping')} subtitle={t('subtitle')} />
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
      <PageHeader
        title={tNav('items.kontoMapping')}
        subtitle={t('subtitle')}
        actions={
          canEdit ? (
            <>
              <Button variant="secondary" iconLeft="upload" onClick={openImport}>
                {t('actions.import')}
              </Button>
              <Button variant="primary" iconLeft="plus" onClick={openCreate}>
                {t('actions.add')}
              </Button>
            </>
          ) : null
        }
      />

      <InlineAlert tone="warning" title={t('intro.title')}>
        {t('intro.text')} <Link href="/exports">{t('intro.link')}</Link>
      </InlineAlert>
      <InlineAlert tone="info" title={t('matching.title')}>
        {t('matching.text')}
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
              icon="table"
              title={t('empty.title')}
              description={t('empty.description')}
              actions={
                canEdit ? (
                  <>
                    <Button variant="primary" iconLeft="plus" onClick={openCreate}>
                      {t('empty.add')}
                    </Button>
                    <Button variant="secondary" iconLeft="upload" onClick={openImport}>
                      {t('empty.import')}
                    </Button>
                  </>
                ) : null
              }
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
                      id="konto-mapping-search"
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
                  rowKey={(row) => row.id}
                  rowActions={(row) =>
                    canEdit ? (
                      <>
                        <Button variant="ghost" size="sm" onClick={() => openEdit(row)}>
                          {t('actions.edit')}
                        </Button>
                        <Button variant="ghost" size="sm" onClick={() => setDeleting(row)}>
                          {t('actions.delete')}
                        </Button>
                      </>
                    ) : null
                  }
                />
              )}
            </>
          )}
        </ResourceView>
      </PageSection>

      <Dialog
        open={form !== null}
        onClose={() => {
          if (!saving) setForm(null);
        }}
        title={form?.id ? t('form.editTitle') : t('form.createTitle')}
        description={t('form.description')}
        size="lg"
        footer={
          <>
            <Button variant="ghost" onClick={() => setForm(null)} disabled={saving}>
              {t('form.cancel')}
            </Button>
            <Button type="submit" form="konto-mapping-form" variant="primary" loading={saving}>
              {t('form.save')}
            </Button>
          </>
        }
      >
        {form ? (
          <form
            id="konto-mapping-form"
            className={styles.form}
            noValidate
            onSubmit={(event) => void save(event)}
          >
            {formError ? <InlineAlert tone="danger">{formError}</InlineAlert> : null}
            <Select
              id="konto-mapping-mandant"
              label={t('form.mandant')}
              hint={mandantsUnavailable ? t('form.mandantUnavailable') : undefined}
              value={form.mandantId}
              options={[{ value: '', label: t('form.allMandants') }, ...mandantOptions]}
              onChange={(event) => setForm({ ...form, mandantId: event.target.value })}
            />
            <Input
              id="konto-mapping-supplier"
              label={t('form.supplier')}
              hint={t('form.supplierHint')}
              value={form.supplier}
              maxLength={400}
              onChange={(event) => setForm({ ...form, supplier: event.target.value })}
            />
            <Select
              id="konto-mapping-category"
              label={t('form.category')}
              value={form.category}
              options={[
                { value: '', label: t('form.allCategories') },
                ...categoryOptions,
              ]}
              onChange={(event) => setForm({ ...form, category: event.target.value })}
            />
            <div className={styles.accountRow}>
              <Input
                id="konto-mapping-sollkonto"
                label={t('form.sollkonto')}
                hint={t('form.accountHint')}
                value={form.sollkonto}
                maxLength={20}
                required
                onChange={(event) => setForm({ ...form, sollkonto: event.target.value })}
              />
              <Input
                id="konto-mapping-habenkonto"
                label={t('form.habenkonto')}
                hint={t('form.accountHint')}
                value={form.habenkonto}
                maxLength={20}
                required
                onChange={(event) => setForm({ ...form, habenkonto: event.target.value })}
              />
            </div>
            <Input
              id="konto-mapping-steuerschluessel"
              label={t('form.taxKey')}
              hint={t('form.taxKeyHint')}
              value={form.steuerschluessel}
              maxLength={20}
              onChange={(event) => setForm({ ...form, steuerschluessel: event.target.value })}
            />
          </form>
        ) : null}
      </Dialog>

      <Dialog
        open={importOpen}
        onClose={() => {
          if (!importing) setImportOpen(false);
        }}
        title={t('import.title')}
        description={t('import.description')}
        footer={
          <>
            <Button variant="ghost" onClick={() => setImportOpen(false)} disabled={importing}>
              {t('import.close')}
            </Button>
            <Button variant="primary" iconLeft="upload" loading={importing} onClick={() => void submitImport()}>
              {t('import.submit')}
            </Button>
          </>
        }
      >
        <p className={styles.hint}>{t('import.columns')}</p>
        <code className={styles.codeBlock}>
          mandant;lieferant;kategorie;sollkonto;habenkonto;steuerschluessel
        </code>
        <Field
          label={t('import.file')}
          hint={t('import.fileHint')}
          id="konto-mapping-import-file"
          error={importError ?? undefined}
        >
          {(ids) => (
            <input
              {...ids}
              className={styles.fileInput}
              type="file"
              accept=".csv,text/csv"
              disabled={importing}
              onChange={(event: ChangeEvent<HTMLInputElement>) => {
                setImportFile(event.target.files?.[0] ?? null);
                setImportError(null);
              }}
            />
          )}
        </Field>

        {report ? (
          <section className={styles.report} aria-labelledby="konto-import-report" data-testid="import-report">
            <h3 className={styles.reportTitle} id="konto-import-report">
              {t('import.reportTitle')}
            </h3>
            <dl className={styles.stats}>
              <div className={styles.stat}>
                <dt>{t('import.created')}</dt>
                <dd className={styles.statValue}>{report.created}</dd>
              </div>
              <div className={styles.stat}>
                <dt>{t('import.updated')}</dt>
                <dd className={styles.statValue}>{report.updated}</dd>
              </div>
              <div className={styles.stat}>
                <dt>{t('import.skipped')}</dt>
                <dd className={styles.statValue}>{report.skipped.length}</dd>
              </div>
            </dl>
            {report.skipped.length === 0 ? (
              <p className={styles.hint}>{t('import.skippedNone')}</p>
            ) : (
              <ul className={styles.skippedList}>
                {report.skipped.map((row) => (
                  <li key={`${row.line}-${row.reason}`}>
                    <strong>{t('import.skippedLine', { line: row.line })}:</strong> {row.reason}
                  </li>
                ))}
              </ul>
            )}
            <p className={styles.hint}>{t('import.unchangedHint')}</p>
          </section>
        ) : null}
      </Dialog>

      <ConfirmDialog
        open={deleting !== null}
        title={t('delete.title')}
        description={
          deleting
            ? `${kontoMappingLabel(deleting, {
                anyMandant: t('table.allMandants'),
                anySupplier: t('table.anySupplier'),
                anyCategory: t('table.anyCategory'),
              })} — ${t('delete.description')}`
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
