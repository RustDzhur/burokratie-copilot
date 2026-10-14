'use client';

import { useState } from 'react';
import { PageHeader, PageSection, ResourceView } from '@/components/app/resource-view';
import { Badge, DataTable, EmptyState } from '@/components/ui';
import type { Column } from '@/components/ui';
import { Button } from '@/components/ui/button';
import { InlineAlert, SkeletonTable } from '@/components/ui/feedback';
import { Input, Select, Textarea } from '@/components/ui/form';
import { Dialog } from '@/components/ui/overlay';
import { useToast } from '@/components/ui/toast';
import { formatDate } from '@/i18n/format';
import { useLocale, useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { toApiError } from '@/lib/api/errors';
import { useApiResource } from '@/lib/api/hooks';
import { useSession } from '@/lib/auth/session-context';
import type { PartyInput, PartySummary } from '@/lib/api/types';

type Cabinet = 'private' | 'family' | 'company';

const PERSON_RELATIONS = ['PARTNER', 'CHILD', 'PARENT', 'RELATIVE', 'OTHER'] as const;
const ORGANIZATION_RELATIONS = ['COMPANY', 'BRANCH'] as const;

const EMPTY: PartyInput = { displayName: '', relation: 'OTHER', birthDate: '', aliases: '', address: '', identifiers: '' };

/**
 * Whom the cabinet belongs to. One person has their own details, a household one entry per member (children and
 * relatives need no login), a company its legal entity and branches. The platform reads the addressee of every
 * letter against these entries, so a letter for a child lands with the child and a company letter with the company.
 */
export default function PartiesPage() {
  const t = useTranslations('parties');
  const tCommon = useTranslations('common');
  const tErrors = useTranslations('errors');
  const locale = useLocale();
  const toast = useToast();
  const user = useSession();
  const cabinet: Cabinet = (user?.workspaceType ?? 'private') as Cabinet;

  const { state, reload, isRefreshing } = useApiResource((signal) => api.parties.list(signal), [], {
    keepPreviousData: true,
  });
  const [editing, setEditing] = useState<{ id: string | null; input: PartyInput; primary: boolean } | null>(null);
  const [pending, setPending] = useState(false);

  const addKey = `action.add.${cabinet}` as 'action.add.private';

  const open = (party?: PartySummary): void => {
    setEditing(
      party
        ? {
            id: party.id,
            primary: party.primary,
            input: {
              displayName: party.displayName,
              relation: party.relation,
              birthDate: party.birthDate ?? '',
              aliases: party.aliases,
              address: party.address,
              identifiers: party.identifiers,
              notes: party.notes ?? '',
            },
          }
        : {
            id: null,
            primary: false,
            input: { ...EMPTY, relation: cabinet === 'company' ? 'BRANCH' : cabinet === 'family' ? 'CHILD' : 'OTHER' },
          },
    );
  };

  const save = async (): Promise<void> => {
    if (!editing) return;
    setPending(true);
    try {
      const input = { ...editing.input, birthDate: editing.input.birthDate || null };
      if (editing.id) await api.parties.update(editing.id, input);
      else await api.parties.create(input);
      toast.success(t('saved'));
      setEditing(null);
      reload();
    } catch (error) {
      toast.error(tErrors(toApiError(error).kind));
    } finally {
      setPending(false);
    }
  };

  const remove = async (party: PartySummary): Promise<void> => {
    if (!window.confirm(t('delete.confirm'))) return;
    try {
      await api.parties.remove(party.id);
      toast.success(t('deleted'));
      reload();
    } catch (error) {
      toast.error(tErrors(toApiError(error).kind));
    }
  };

  const columns: readonly Column<PartySummary>[] = [
    {
      key: 'name',
      header: t('column.name'),
      render: (row) => (
        <>
          {row.displayName} {row.primary ? <Badge tone="info">{t('main.badge')}</Badge> : null}
        </>
      ),
    },
    {
      key: 'relation',
      header: t('column.relation'),
      render: (row) => t(`relation.${row.relation}` as 'relation.SELF'),
    },
    ...(cabinet === 'company'
      ? []
      : [
          {
            key: 'birth',
            header: t('column.birth'),
            render: (row: PartySummary) => (row.birthDate ? formatDate(row.birthDate, locale) : '—'),
          },
        ]),
    { key: 'documents', header: t('column.documents'), align: 'end', render: (row) => row.documentCount },
  ];

  const relations = editing?.id
    ? [...(editing.primary && cabinet !== 'company' ? (['SELF'] as const) : []), ...(cabinet === 'company' ? ORGANIZATION_RELATIONS : PERSON_RELATIONS)]
    : cabinet === 'company'
      ? [...ORGANIZATION_RELATIONS]
      : [...PERSON_RELATIONS];
  const isOrganization = cabinet === 'company';
  const set = (patch: Partial<PartyInput>): void =>
    setEditing((current) => (current ? { ...current, input: { ...current.input, ...patch } } : current));

  return (
    <>
      <PageHeader
        title={t(`title.${cabinet}` as 'title.private')}
        subtitle={t(`subtitle.${cabinet}` as 'subtitle.private')}
        actions={
          <Button variant="primary" iconLeft="plus" onClick={() => open()}>
            {t(addKey)}
          </Button>
        }
      />
      <InlineAlert tone="info">{t('howItWorks')}</InlineAlert>
      <PageSection title={t(`title.${cabinet}` as 'title.private')}>
        <ResourceView
          state={state}
          onRetry={reload}
          isRefreshing={isRefreshing}
          skeleton={<SkeletonTable rows={3} columns={4} />}
          isEmpty={(data) => data.length === 0}
          emptyState={<EmptyState icon="users" title={t('empty.title')} description={t(`subtitle.${cabinet}` as 'subtitle.private')} />}
        >
          {(data) => (
            <DataTable
              caption={t(`title.${cabinet}` as 'title.private')}
              columns={columns}
              rows={data}
              rowKey={(row) => row.id}
              rowActions={(row) => (
                <>
                  <Button variant="ghost" onClick={() => open(row)}>
                    {t('action.edit')}
                  </Button>
                  {row.primary ? null : (
                    <Button variant="ghost" iconLeft="trash" onClick={() => void remove(row)}>
                      {t('action.delete')}
                    </Button>
                  )}
                </>
              )}
            />
          )}
        </ResourceView>
      </PageSection>

      <Dialog
        open={editing !== null}
        onClose={() => setEditing(null)}
        title={editing?.id ? t('action.edit') : t(addKey)}
        footer={
          <>
            <Button variant="ghost" onClick={() => setEditing(null)} disabled={pending}>
              {tCommon('actions.cancel')}
            </Button>
            <Button
              variant="primary"
              loading={pending}
              disabled={!editing?.input.displayName.trim()}
              onClick={() => void save()}
            >
              {t('action.save')}
            </Button>
          </>
        }
      >
        {editing ? (
          <div style={{ display: 'grid', gap: '0.75rem' }}>
            <Input
              label={isOrganization ? t('field.name.organization') : t('field.name.person')}
              value={editing.input.displayName}
              onChange={(event) => set({ displayName: event.target.value })}
              required
            />
            {editing.primary && !isOrganization ? null : (
              <Select
                label={t('field.relation')}
                options={relations.map((value) => ({ value, label: t(`relation.${value}` as 'relation.SELF') }))}
                value={editing.input.relation}
                onChange={(event) => set({ relation: event.target.value })}
              />
            )}
            {isOrganization ? null : (
              <Input
                type="date"
                label={t('field.birthDate')}
                value={editing.input.birthDate ?? ''}
                onChange={(event) => set({ birthDate: event.target.value })}
              />
            )}
            <Textarea
              label={t('field.aliases')}
              hint={t('field.aliases.hint')}
              value={editing.input.aliases}
              onChange={(event) => set({ aliases: event.target.value })}
            />
            <Textarea
              label={t('field.address')}
              value={editing.input.address}
              onChange={(event) => set({ address: event.target.value })}
            />
            <Textarea
              label={t('field.identifiers')}
              hint={t('field.identifiers.hint')}
              value={editing.input.identifiers}
              onChange={(event) => set({ identifiers: event.target.value })}
            />
          </div>
        ) : null}
      </Dialog>
    </>
  );
}
