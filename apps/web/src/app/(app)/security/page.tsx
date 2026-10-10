'use client';

import { useState } from 'react';
import { CreateRecordDialog } from '@/components/app/create-record-dialog';
import { PageHeader, PageSection, ResourceView } from '@/components/app/resource-view';
import {
  Card,
  CardBody,
  CardHeader,
  DataTable,
  EmptyState,
  StatusPill,
  Switch,
} from '@/components/ui';
import type { Column } from '@/components/ui';
import { Button } from '@/components/ui/button';
import { InlineAlert, SkeletonCards } from '@/components/ui/feedback';
import { useToast } from '@/components/ui/toast';
import { formatDateTime } from '@/i18n/format';
import { useLocale, useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { toApiError } from '@/lib/api/errors';
import { useApiResource } from '@/lib/api/hooks';
import type { SessionInfo } from '@/lib/api/types';
import styles from './security.module.css';

/**
 * Security settings (§5.2.27).
 *
 * MFA, password changes and session revocation are executed by the backend; the
 * screen only reflects what the backend reports, and the policy notice states
 * clearly that enforcement happens server-side (§21.3).
 */
export default function SecurityPage() {
  const t = useTranslations('security');
  const tNav = useTranslations('nav');
  const tCommon = useTranslations('common');
  const tErrors = useTranslations('errors');
  const tStates = useTranslations('states');
  const locale = useLocale();
  const toast = useToast();

  const [passwordOpen, setPasswordOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [mfaPending, setMfaPending] = useState(false);

  const { state, reload, isRefreshing } = useApiResource(
    (signal) => api.settings.security(signal),
    [],
  );

  const toggleMfa = async (enabled: boolean): Promise<void> => {
    setMfaPending(true);
    try {
      await api.settings.setMfa(enabled);
      toast.success(enabled ? t('mfa.enabled') : t('mfa.disabled'));
      reload();
    } catch (error) {
      toast.error(tErrors(toApiError(error).kind));
    } finally {
      setMfaPending(false);
    }
  };

  const revoke = async (session: SessionInfo): Promise<void> => {
    setBusy(true);
    try {
      await api.settings.revokeSession(session.id);
      toast.success(t('action.revoke'), session.device);
      reload();
    } catch (error) {
      toast.error(tErrors(toApiError(error).kind));
    } finally {
      setBusy(false);
    }
  };

  const changePassword = async (values: Record<string, string>): Promise<void> => {
    await api.settings.changePassword({
      currentPassword: values.currentPassword ?? '',
      newPassword: values.newPassword ?? '',
    });
    toast.success(t('password.changed'));
    reload();
  };

  const columns: readonly Column<SessionInfo>[] = [
    { key: 'device', header: t('session.device'), render: (row) => row.device },
    {
      key: 'location',
      header: t('session.location'),
      render: (row) => row.location ?? tCommon('labels.unknown'),
    },
    {
      key: 'lastActive',
      header: t('session.lastActive'),
      render: (row) => formatDateTime(row.lastActiveAt, locale),
    },
    {
      key: 'current',
      header: tCommon('labels.status'),
      render: (row) => (
        <StatusPill
          label={row.current ? t('session.current') : t('action.revoke')}
          tone={row.current ? 'success' : 'neutral'}
        />
      ),
    },
  ];

  return (
    <>
      <PageHeader
        title={tNav('items.security')}
        subtitle={t('subtitle')}
        actions={
          <Button variant="secondary" iconLeft="refresh" onClick={reload} loading={isRefreshing}>
            {tCommon('actions.refresh')}
          </Button>
        }
      />

      <ResourceView
        state={state}
        onRetry={reload}
        isRefreshing={isRefreshing}
        skeleton={<SkeletonCards count={2} />}
        emptyState={
          <EmptyState
            icon="shield"
            title={tStates('empty.title')}
            description={tStates('empty.description')}
          />
        }
      >
        {(data) => (
          <>
            <div className={styles.grid}>
              <Card>
                <CardHeader
                  title={t('section.password')}
                  description={t('policy.notice')}
                  icon="key"
                  headingLevel={2}
                />
                <CardBody>
                  <Button variant="secondary" iconLeft="lock" onClick={() => setPasswordOpen(true)}>
                    {t('action.changePassword')}
                  </Button>
                </CardBody>
              </Card>

              <Card>
                <CardHeader
                  title={t('section.mfa')}
                  description={data.mfaEnabled ? t('mfa.enabled') : t('mfa.disabled')}
                  icon="shield"
                  headingLevel={2}
                />
                <CardBody>
                  <Switch
                    label={data.mfaEnabled ? t('action.disableMfa') : t('action.enableMfa')}
                    description={t('mfa.recommended')}
                    checked={data.mfaEnabled}
                    disabled={mfaPending}
                    onChange={(checked) => void toggleMfa(checked)}
                  />
                </CardBody>
              </Card>
            </div>

            <PageSection title={t('section.sessions')}>
              {data.sessions.length === 0 ? (
                <EmptyState icon="lock" title={t('empty.sessions')} />
              ) : (
                <DataTable
                  caption={t('section.sessions')}
                  columns={columns}
                  rows={data.sessions}
                  rowKey={(row) => row.id}
                  rowActions={(row) =>
                    row.current ? null : (
                      <Button
                        variant="ghost"
                        size="sm"
                        disabled={busy}
                        onClick={() => void revoke(row)}
                      >
                        {t('action.revoke')}
                      </Button>
                    )
                  }
                />
              )}
            </PageSection>

            <PageSection title={t('section.policy')}>
              <InlineAlert tone="info">{t('policy.notice')}</InlineAlert>
              <ul className={styles.policyList}>
                {Object.entries(data.policy).map(([key, value]) => (
                  <li key={key}>
                    <span>{key}</span>
                    <strong>{String(value)}</strong>
                  </li>
                ))}
              </ul>
            </PageSection>
          </>
        )}
      </ResourceView>

      <CreateRecordDialog
        open={passwordOpen}
        onClose={() => setPasswordOpen(false)}
        title={t('action.changePassword')}
        submitLabel={tCommon('actions.save')}
        fields={[
          { name: 'currentPassword', label: t('password.current'), type: 'text', required: true },
          { name: 'newPassword', label: t('password.new'), type: 'text', required: true },
        ]}
        onSubmit={changePassword}
      />
    </>
  );
}
