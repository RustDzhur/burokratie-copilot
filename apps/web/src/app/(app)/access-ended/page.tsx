'use client';

import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useState } from 'react';
import { PageHeader, PageSection, ResourceView } from '@/components/app/resource-view';
import { Card, CardBody, CardHeader } from '@/components/ui';
import { Button } from '@/components/ui/button';
import { InlineAlert } from '@/components/ui/feedback';
import { Input } from '@/components/ui/form';
import { Dialog } from '@/components/ui/overlay';
import { useToast } from '@/components/ui/toast';
import { formatDate } from '@/i18n/format';
import { useLocale, useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { toApiError } from '@/lib/api/errors';
import { useApiResource } from '@/lib/api/hooks';
import type { BillingState } from '@/lib/api/types';
import styles from '../billing/billing.module.css';

/**
 * The only page of a workspace whose access has ended. The owner decides what happens: renew, keep the data until
 * the retention date, or delete it now. Everybody else is told whom to ask.
 */
export default function AccessEndedPage() {
  const t = useTranslations('accessEnded');
  const tBilling = useTranslations('billing');
  const tErrors = useTranslations('errors');
  const tNav = useTranslations('nav');
  const locale = useLocale();
  const toast = useToast();
  const router = useRouter();
  const { state, reload } = useApiResource<BillingState>((signal) => api.billing.state(signal), []);
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [word, setWord] = useState('');

  const keep = async (date: string): Promise<void> => {
    try {
      await api.billing.keepData();
      toast.success(t('keep.saved', { date }));
      reload();
    } catch (caught) {
      toast.error(t('choice.keep', { date }), tErrors(toApiError(caught).kind));
    }
  };

  const remove = async (): Promise<void> => {
    try {
      await api.billing.deleteData();
      toast.success(tBilling('data.deleted'));
      router.replace('/login');
    } catch (caught) {
      toast.error(t('choice.delete'), tErrors(toApiError(caught).kind));
    }
  };

  return (
    <>
      <PageHeader title={t('title')} />
      <ResourceView state={state} onRetry={reload} isEmpty={() => false} emptyState={null}>
        {(data) => {
          const date = data.retentionUntil ? formatDate(data.retentionUntil, locale) : '';
          return (
            <>
              <InlineAlert tone="warning">
                {data.status === 'SUSPENDED' ? t('bodySuspended') : t('body', { date })}
              </InlineAlert>
              {data.owner ? (
                <p className={styles.muted}>
                  <a href={api.billing.exportUrl()} download>
                    {tBilling('data.export')}
                  </a>
                </p>
              ) : null}
              {!data.owner ? (
                <p className={styles.muted}>{t('notOwner')}</p>
              ) : data.status === 'SUSPENDED' ? null : (
                <PageSection title={t('choice.title')}>
                  <ul className={styles.plans}>
                    <li>
                      <Card>
                        <CardHeader title={t('choice.renew')} icon="credit-card" />
                        <CardBody>
                          <p className={styles.muted}>{t('choice.renewHint')}</p>
                          <Link href="/billing">
                            <Button variant="primary">{t('choice.renew')}</Button>
                          </Link>
                        </CardBody>
                      </Card>
                    </li>
                    <li>
                      <Card>
                        <CardHeader title={t('choice.keep', { date })} icon="archive" />
                        <CardBody>
                          <p className={styles.muted}>
                            {data.dataChoice === 'KEEP' ? t('keep.saved', { date }) : t('choice.keepHint')}
                          </p>
                          <Button variant="secondary" onClick={() => void keep(date)} disabled={data.dataChoice === 'KEEP'}>
                            {t('choice.keep', { date })}
                          </Button>
                        </CardBody>
                      </Card>
                    </li>
                    <li>
                      <Card>
                        <CardHeader title={t('choice.delete')} icon="trash" />
                        <CardBody>
                          <p className={styles.muted}>{t('choice.deleteHint')}</p>
                          <Button variant="danger" onClick={() => setDeleteOpen(true)}>
                            {t('choice.delete')}
                          </Button>
                        </CardBody>
                      </Card>
                    </li>
                  </ul>
                </PageSection>
              )}
            </>
          );
        }}
      </ResourceView>
      <p className={styles.muted}>{tNav('items.accessEnded')}</p>
      <Dialog
        open={deleteOpen}
        onClose={() => setDeleteOpen(false)}
        title={tBilling('data.deleteTitle')}
        description={tBilling('data.deleteBody')}
        footer={
          <Button variant="danger" disabled={word !== 'DELETE'} onClick={() => void remove()}>
            {t('choice.delete')}
          </Button>
        }
      >
        <Input label={tBilling('data.deleteConfirm')} value={word} onChange={(event) => setWord(event.target.value)} />
      </Dialog>
    </>
  );
}
