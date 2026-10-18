'use client';

import Link from 'next/link';
import { useMemo, useState, type FormEvent } from 'react';
import { PageHeader, ResourceView } from '@/components/app/resource-view';
import { Badge, Card, CardBody, EmptyState } from '@/components/ui';
import { Button } from '@/components/ui/button';
import { InlineAlert, SkeletonText } from '@/components/ui/feedback';
import { Input, SearchInput, Select, Switch } from '@/components/ui/form';
import { ConfirmDialog } from '@/components/ui/overlay';
import { useToast } from '@/components/ui/toast';
import { useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { toApiError } from '@/lib/api/errors';
import { useApiResource } from '@/lib/api/hooks';
import { useSession } from '@/lib/auth/session-context';
import type { DateMarker, DateMarkerKind } from '@/lib/api/types';
import {
  countDateMarkers,
  DATE_MARKER_KINDS,
  filterDateMarkers,
  groupDateMarkers,
  type KindFilter,
} from './date-marker-model';
import styles from './date-markers.module.css';

const NO_MARKERS: readonly DateMarker[] = [];

/**
 * The vocabulary that explains a date (§9.3): the platform's default words, the workspace's own words
 * and the defaults it switched off.
 *
 * <p>The platform defaults are owned by the platform and cannot be patched (409). Switching one off is
 * therefore an own word with the same kind and phrase and `enabled=false`; deleting that own word brings
 * the default back. The screen says that in words, because "off" otherwise looks like an edit that did
 * not save.</p>
 */
export default function DateMarkersPage() {
  const t = useTranslations('dateMarkers');
  const tNav = useTranslations('nav');
  const tErrors = useTranslations('errors');
  const toast = useToast();
  const session = useSession();

  // The server decides; the screen only avoids showing what would be refused anyway. An unknown
  // permission list (an older session, a test harness) hides nothing.
  const permissions = session?.permissions;
  const canView = permissions === undefined || permissions.includes('settings.view');
  const canEdit = permissions?.includes('settings.manage_rules') === true;
  const showReadOnly = permissions !== undefined && canView && !canEdit;

  const { state, reload, isRefreshing } = useApiResource(
    (signal) => api.settings.dateMarkers.list(signal),
    [],
    // A toggle must not blank 255 rows: the list stays while the new state is fetched.
    { skip: !canView, keepPreviousData: true },
  );

  const [query, setQuery] = useState('');
  const [kindFilter, setKindFilter] = useState<KindFilter>('ALL');
  const [busyId, setBusyId] = useState<string | null>(null);
  const [confirmReset, setConfirmReset] = useState(false);
  const [resetting, setResetting] = useState(false);
  const [newKind, setNewKind] = useState<DateMarkerKind>('DEADLINE');
  const [phrase, setPhrase] = useState('');
  const [label, setLabel] = useState('');
  const [phraseError, setPhraseError] = useState<string | undefined>(undefined);
  const [adding, setAdding] = useState(false);

  const markers = state.status === 'ready' ? state.data.items : NO_MARKERS;
  const counts = useMemo(() => countDateMarkers(markers), [markers]);
  const filtered = useMemo(
    () => filterDateMarkers(markers, { query, kind: kindFilter }),
    [markers, query, kindFilter],
  );
  const groups = useMemo(() => groupDateMarkers(filtered), [filtered]);

  const switchEnabled = async (marker: DateMarker, enabled: boolean): Promise<void> => {
    setBusyId(marker.id);
    try {
      if (marker.system) {
        // A default cannot be patched: the own word with the same kind and phrase is the switch-off.
        await api.settings.dateMarkers.create({
          kind: marker.kind,
          phrase: marker.phrase,
          label: marker.label ?? undefined,
          enabled: false,
        });
        toast.success(t('list.systemDisabled'));
      } else {
        await api.settings.dateMarkers.update(marker.id, { enabled });
        toast.success(enabled ? t('list.enabled') : t('list.disabled'));
      }
      reload();
    } catch (error) {
      toast.error(tErrors(toApiError(error).kind));
    } finally {
      setBusyId(null);
    }
  };

  const remove = async (marker: DateMarker): Promise<void> => {
    setBusyId(marker.id);
    try {
      await api.settings.dateMarkers.remove(marker.id);
      toast.success(t('list.deleted'));
      reload();
    } catch (error) {
      toast.error(tErrors(toApiError(error).kind));
    } finally {
      setBusyId(null);
    }
  };

  const resetToDefaults = async (): Promise<void> => {
    setResetting(true);
    try {
      const { deleted } = await api.settings.dateMarkers.reset();
      toast.success(t('reset.done', { deleted }));
      setConfirmReset(false);
      reload();
    } catch (error) {
      toast.error(tErrors(toApiError(error).kind));
    } finally {
      setResetting(false);
    }
  };

  const addWord = async (event: FormEvent<HTMLFormElement>): Promise<void> => {
    event.preventDefault();
    if (phrase.trim().length < 2) {
      setPhraseError(t('add.required'));
      return;
    }
    setPhraseError(undefined);
    setAdding(true);
    try {
      await api.settings.dateMarkers.create({
        kind: newKind,
        phrase: phrase.trim(),
        label: label.trim() === '' ? undefined : label.trim(),
      });
      toast.success(t('add.added'));
      setPhrase('');
      setLabel('');
      reload();
    } catch (error) {
      const apiError = toApiError(error);
      if (apiError.kind === 'conflict') setPhraseError(t('add.duplicate'));
      else toast.error(tErrors(apiError.kind));
    } finally {
      setAdding(false);
    }
  };

  if (!canView) {
    return (
      <>
        <PageHeader title={tNav('items.dateMarkers')} subtitle={t('subtitle')} />
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
        title={tNav('items.dateMarkers')}
        subtitle={t('subtitle')}
        actions={
          canEdit ? (
            <Button variant="secondary" onClick={() => setConfirmReset(true)}>
              {t('reset.action')}
            </Button>
          ) : null
        }
      />

      <InlineAlert tone="info" title={t('hint.title')}>
        {t('hint.text')}
      </InlineAlert>
      {showReadOnly ? <InlineAlert tone="neutral">{t('readOnly')}</InlineAlert> : null}

      <ResourceView
        state={state}
        onRetry={reload}
        isRefreshing={isRefreshing}
        isEmpty={(data) => data.items.length === 0}
        emptyState={
          <EmptyState
            icon="calendar"
            title={t('empty.title')}
            description={t('empty.description')}
          />
        }
        skeleton={
          <Card>
            <CardBody>
              <SkeletonText lines={6} />
            </CardBody>
          </Card>
        }
      >
        {(data) => (
          <>
            <Card>
              <CardBody>
                <p className={styles.intro}>
                  <strong>{t('intro.title')}:</strong> {t('intro.text')}
                </p>
                <p className={styles.summary}>
                  {t('summary', { standard: counts.standard, own: counts.own })}
                </p>
                <div className={styles.filters}>
                  <SearchInput
                    id="date-marker-search"
                    label={t('filter.search')}
                    value={query}
                    onChange={(event) => setQuery(event.target.value)}
                  />
                  <Select
                    id="date-marker-filter-kind"
                    label={t('filter.kind')}
                    value={kindFilter}
                    onChange={(event) => setKindFilter(event.target.value as KindFilter)}
                  >
                    <option value="ALL">{t('filter.allKinds')}</option>
                    {DATE_MARKER_KINDS.map((kind) => (
                      <option key={kind} value={kind}>
                        {t(`kind.${kind}`)}
                      </option>
                    ))}
                  </Select>
                  <Button
                    variant="ghost"
                    onClick={() => {
                      setQuery('');
                      setKindFilter('ALL');
                    }}
                  >
                    {t('filter.clear')}
                  </Button>
                </div>
                <p className={styles.shown} aria-live="polite">
                  {t('filter.shown', { shown: filtered.length, total: data.count })}
                </p>
              </CardBody>
            </Card>

            {groups.length === 0 ? (
              <InlineAlert tone="info" title={t('filter.noMatches')}>
                {t('filter.noMatchesHint')}
              </InlineAlert>
            ) : (
              <div className={styles.groups}>
                {groups.map((group) => (
                  <section key={group.kind} aria-labelledby={`date-kind-${group.kind}`}>
                    <h2 className={styles.groupTitle} id={`date-kind-${group.kind}`}>
                      {t(`kind.${group.kind}`)} <Badge tone="neutral">{group.items.length}</Badge>
                    </h2>
                    <Card>
                      <CardBody>
                        <ul className={styles.list}>
                          {group.items.map((marker) => (
                            <li
                              key={marker.id}
                              className={styles.row}
                              data-testid="date-marker"
                              data-kind={marker.kind}
                            >
                              <div className={styles.rowMain}>
                                <span className={styles.phrase}>{marker.phrase}</span>
                                {marker.label ? (
                                  <span className={styles.label}>{marker.label}</span>
                                ) : null}
                                <span className={styles.badges}>
                                  {marker.system ? (
                                    <Badge tone="neutral">{t('list.standard')}</Badge>
                                  ) : (
                                    <Badge tone="info">{t('list.own')}</Badge>
                                  )}
                                  {marker.tenantOverride ? (
                                    <Badge tone="accent">{t('list.overrides')}</Badge>
                                  ) : null}
                                  {marker.enabled ? null : (
                                    <Badge tone="warning">{t('list.inactive')}</Badge>
                                  )}
                                </span>
                              </div>
                              <div className={styles.rowActions}>
                                <Switch
                                  label={t('list.enabledLabel')}
                                  checked={marker.enabled}
                                  disabled={!canEdit || busyId === marker.id}
                                  onChange={(enabled) => void switchEnabled(marker, enabled)}
                                />
                                {marker.system || !canEdit ? null : (
                                  <Button
                                    variant="ghost"
                                    size="sm"
                                    loading={busyId === marker.id}
                                    onClick={() => void remove(marker)}
                                  >
                                    {t('list.delete')}
                                  </Button>
                                )}
                              </div>
                            </li>
                          ))}
                        </ul>
                      </CardBody>
                    </Card>
                  </section>
                ))}
              </div>
            )}
          </>
        )}
      </ResourceView>

      {canEdit && state.status !== 'error' ? (
        <section className={styles.addSection} aria-labelledby="date-marker-add-title">
          <h2 className={styles.groupTitle} id="date-marker-add-title">
            {t('add.title')}
          </h2>
          <Card>
            <CardBody>
              <p className={styles.intro}>{t('add.description')}</p>
              <form className={styles.addForm} onSubmit={(event) => void addWord(event)}>
                <Select
                  id="date-marker-add-kind"
                  label={t('add.kind')}
                  value={newKind}
                  onChange={(event) => setNewKind(event.target.value as DateMarkerKind)}
                >
                  {DATE_MARKER_KINDS.map((kind) => (
                    <option key={kind} value={kind}>
                      {t(`kind.${kind}`)}
                    </option>
                  ))}
                </Select>
                <Input
                  id="date-marker-add-phrase"
                  label={t('add.phrase')}
                  hint={t('add.phraseHint')}
                  error={phraseError}
                  value={phrase}
                  maxLength={120}
                  required
                  onChange={(event) => {
                    setPhrase(event.target.value);
                    if (phraseError) setPhraseError(undefined);
                  }}
                />
                <Input
                  id="date-marker-add-label"
                  label={t('add.label')}
                  hint={t('add.labelHint')}
                  value={label}
                  maxLength={200}
                  onChange={(event) => setLabel(event.target.value)}
                />
                <Button type="submit" variant="primary" loading={adding}>
                  {t('add.submit')}
                </Button>
              </form>
            </CardBody>
          </Card>
        </section>
      ) : null}

      <ConfirmDialog
        open={confirmReset}
        title={t('reset.title')}
        description={t('reset.description')}
        confirmLabel={t('reset.confirm')}
        loading={resetting}
        onConfirm={() => void resetToDefaults()}
        onCancel={() => setConfirmReset(false)}
      />
    </>
  );
}
