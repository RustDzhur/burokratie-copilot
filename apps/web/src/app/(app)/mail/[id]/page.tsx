'use client';

import Link from 'next/link';
import { useParams, useRouter } from 'next/navigation';
import { useState } from 'react';
import { PageHeader, ResourceView } from '@/components/app/resource-view';
import { Badge, Card, CardBody } from '@/components/ui';
import { Button } from '@/components/ui/button';
import { buttonClass } from '@/components/ui/button-class';
import { InlineAlert, SkeletonText } from '@/components/ui/feedback';
import { useToast } from '@/components/ui/toast';
import { formatDateTime } from '@/i18n/format';
import { useLocale, useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { toApiError } from '@/lib/api/errors';
import { useApiResource } from '@/lib/api/hooks';

/** One message of the mail centre, read as plain text (the content comes from outside and is never rendered as HTML). */
export default function MailMessagePage() {
  const { id } = useParams<{ id: string }>();
  const t = useTranslations('mail');
  const tCommon = useTranslations('common');
  const tErrors = useTranslations('errors');
  const locale = useLocale();
  const toast = useToast();
  const router = useRouter();
  const [busy, setBusy] = useState(false);
  const { state, reload, isRefreshing } = useApiResource((signal) => api.mail.get(id, signal), [id]);

  const remove = async (): Promise<void> => {
    if (!window.confirm(tCommon('bulk.confirmOne'))) return;
    setBusy(true);
    try {
      await api.deletion.remove('MAIL', [id]);
      toast.success(tCommon('bulk.doneOne'));
      router.push('/mail');
    } catch (error) {
      toast.error(tErrors(toApiError(error).kind));
      setBusy(false);
    }
  };

  return (
    <>
      <PageHeader
        title={state.status === 'ready' ? state.data.subject || '—' : t('detail.title')}
        subtitle={t('detail.subtitle')}
        actions={
          <>
            <Link href="/mail" className={buttonClass({ variant: 'secondary' })}>
              {t('detail.back')}
            </Link>
            <Button variant="danger" iconLeft="trash" loading={busy} onClick={() => void remove()}>
              {tCommon('actions.delete')}
            </Button>
          </>
        }
      />
      <ResourceView
        state={state}
        onRetry={reload}
        isRefreshing={isRefreshing}
        isEmpty={() => false}
        emptyState={null}
        skeleton={
          <Card>
            <CardBody>
              <SkeletonText lines={6} />
            </CardBody>
          </Card>
        }
      >
        {(message) => (
          <Card>
            <CardBody>
              {message.is_suspicious ? <InlineAlert tone="warning">{t('detail.suspicious')}</InlineAlert> : null}
              <dl style={{ display: 'grid', gridTemplateColumns: 'max-content 1fr', gap: '0.25rem 1rem', margin: '0 0 1rem' }}>
                <dt>{t('detail.from')}</dt>
                <dd style={{ margin: 0 }}>
                  {message.from_name ? `${message.from_name} ` : ''}
                  {message.from_address ? `<${message.from_address}>` : ''}
                </dd>
                <dt>{t('detail.to')}</dt>
                <dd style={{ margin: 0 }}>{message.to_addresses ?? '—'}</dd>
                <dt>{t('detail.date')}</dt>
                <dd style={{ margin: 0 }}>{formatDateTime(message.moment, locale)}</dd>
                <dt>{t('detail.attachments')}</dt>
                <dd style={{ margin: 0 }}>
                  <Badge tone="neutral">{message.attachment_count}</Badge>
                </dd>
              </dl>
              {message.body.trim() === '' ? (
                <p>{t('detail.empty')}</p>
              ) : (
                <pre style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', margin: 0, font: 'inherit' }}>
                  {message.body}
                </pre>
              )}
            </CardBody>
          </Card>
        )}
      </ResourceView>
    </>
  );
}
