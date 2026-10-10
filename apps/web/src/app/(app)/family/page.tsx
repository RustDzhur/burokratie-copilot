'use client';

import { useState } from 'react';
import { CreateRecordDialog } from '@/components/app/create-record-dialog';
import { PageHeader, PageSection, ResourceView } from '@/components/app/resource-view';
import { Card, CardBody, CardHeader, DataTable, EmptyState, StatusPill } from '@/components/ui';
import type { Column } from '@/components/ui';
import { Button } from '@/components/ui/button';
import { InlineAlert, SkeletonTable } from '@/components/ui/feedback';
import { useToast } from '@/components/ui/toast';
import { useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { useApiResource } from '@/lib/api/hooks';
import type { MemberSummary } from '@/lib/api/types';
import { useGroupedLabels, useStatusLabels } from '@/lib/format/status';
import styles from './family.module.css';

const ROLE_VALUES = ['owner', 'member'] as const;

/**
 * Family cockpit (§5.2.22): shared household administration.
 *
 * Personal documents stay separate unless explicitly released — that notice is
 * part of the screen, not a footnote in a policy document.
 */
export default function FamilyPage() {
  const t = useTranslations('family');
  const tNav = useTranslations('nav');
  const tCommon = useTranslations('common');
  const tMembers = useTranslations('employees');
  const toast = useToast();
  const statusLabel = useStatusLabels('family');
  const roleLabel = useGroupedLabels('family', 'role');
  const [inviteOpen, setInviteOpen] = useState(false);

  const { state, reload, isRefreshing } = useApiResource(
    (signal) => api.members.list({ size: 50 }, signal),
    [],
    { keepPreviousData: true },
  );

  const columns: readonly Column<MemberSummary>[] = [
    { key: 'name', header: t('column.name'), render: (row) => row.name },
    { key: 'role', header: t('column.role'), render: (row) => roleLabel(row.role) },
    {
      key: 'documents',
      header: t('column.documents'),
      align: 'end',
      render: (row) => row.documentCount ?? 0,
    },
    {
      key: 'status',
      header: t('column.status'),
      render: (row) => <StatusPill label={statusLabel(row.status)} status={row.status} />,
    },
  ];

  const invite = async (values: Record<string, string>): Promise<void> => {
    await api.members.invite({
      email: values.email ?? '',
      role: (values.role ?? 'member') as MemberSummary['role'],
    });
    toast.success(t('action.invite'));
    reload();
  };

  const documentTotal =
    state.status === 'ready'
      ? state.data.items.reduce((total, member) => total + (member.documentCount ?? 0), 0)
      : 0;

  return (
    <>
      <PageHeader
        title={tNav('items.family')}
        subtitle={t('subtitle')}
        actions={
          <Button variant="primary" iconLeft="plus" onClick={() => setInviteOpen(true)}>
            {t('action.invite')}
          </Button>
        }
      />

      <InlineAlert tone="info">{t('privacy.notice')}</InlineAlert>

      <PageSection title={t('members.title')}>
        <ResourceView
          state={state}
          onRetry={reload}
          isRefreshing={isRefreshing}
          skeleton={<SkeletonTable rows={4} columns={4} />}
          isEmpty={(data) => data.items.length === 0}
          emptyState={
            <EmptyState
              icon="users"
              title={t('empty.title')}
              description={t('empty.description')}
              actions={
                <Button variant="primary" iconLeft="plus" onClick={() => setInviteOpen(true)}>
                  {t('empty.action')}
                </Button>
              }
            />
          }
        >
          {(data) => (
            <DataTable
              caption={t('members.title')}
              columns={columns}
              rows={data.items}
              rowKey={(row) => row.id}
            />
          )}
        </ResourceView>
      </PageSection>

      <PageSection title={t('sharedDocuments')}>
        <Card>
          <CardHeader
            title={t('sharedDocuments')}
            description={t('privacy.notice')}
            icon="folder"
            headingLevel={3}
          />
          <CardBody>
            <dl className={styles.metrics}>
              <div>
                <dt>{t('column.documents')}</dt>
                <dd>{documentTotal}</dd>
              </div>
              <div>
                <dt>{tMembers('column.role')}</dt>
                <dd>{state.status === 'ready' ? state.data.items.length : 0}</dd>
              </div>
            </dl>
          </CardBody>
        </Card>
      </PageSection>

      <CreateRecordDialog
        open={inviteOpen}
        onClose={() => setInviteOpen(false)}
        title={t('action.invite')}
        submitLabel={tCommon('actions.create')}
        fields={[
          { name: 'email', label: tNav('items.mail'), type: 'email', required: true },
          {
            name: 'role',
            label: t('column.role'),
            type: 'select',
            options: ROLE_VALUES.map((value) => ({ value, label: roleLabel(value) })),
          },
        ]}
        onSubmit={invite}
      />
    </>
  );
}
