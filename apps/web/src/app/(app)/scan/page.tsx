'use client';

import { useState } from 'react';
import { PageHeader, PageSection, ResourceView } from '@/components/app/resource-view';
import { Badge, Card, CardBody, CardHeader, EmptyState, StatusPill } from '@/components/ui';
import { Button } from '@/components/ui/button';
import { FileDropzone, UploadList, useUploadQueue } from '@/components/ui/file-dropzone';
import { InlineAlert, SkeletonCards } from '@/components/ui/feedback';
import { useToast } from '@/components/ui/toast';
import { formatDateTime } from '@/i18n/format';
import { useLocale, useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { toApiError } from '@/lib/api/errors';
import { useApiResource } from '@/lib/api/hooks';
import type { DeviceSummary } from '@/lib/api/types';
import styles from './scan.module.css';

/**
 * Scanning and import (§5.2.21).
 *
 * Local scanners are reached through the Desktop Bridge; without a bridge or a
 * connected device the page offers file import and says exactly what is missing
 * instead of simulating a scanner.
 */
export default function ScanPage() {
  const t = useTranslations('scan');
  const tNav = useTranslations('nav');
  const tCommon = useTranslations('common');
  const tErrors = useTranslations('errors');
  const tStates = useTranslations('states');
  const locale = useLocale();
  const toast = useToast();
  const [busyDevice, setBusyDevice] = useState<string | null>(null);

  const { state, reload, isRefreshing } = useApiResource((signal) => api.devices.list(signal), []);

  const queue = useUploadQueue({
    upload: async (file, context) => {
      await api.documents.upload(file, {
        onProgress: context.onProgress,
        signal: context.signal,
        idempotencyKey: true,
      });
    },
    onUploaded: () => toast.success(tStates('success.created')),
    onFailed: (_file, message) => toast.error(tErrors('network'), message),
  });

  const startScan = async (device: DeviceSummary): Promise<void> => {
    setBusyDevice(device.id);
    try {
      await api.devices.scan(device.id);
      toast.success(t('action.scanNow'), device.name);
    } catch (error) {
      toast.error(tErrors(toApiError(error).kind));
    } finally {
      setBusyDevice(null);
    }
  };

  return (
    <>
      <PageHeader
        title={tNav('items.scan')}
        subtitle={t('subtitle')}
        actions={
          <Button variant="secondary" iconLeft="refresh" onClick={reload} loading={isRefreshing}>
            {tCommon('actions.refresh')}
          </Button>
        }
      />

      <div className={styles.layout}>
        <PageSection title={t('device.title')}>
          <ResourceView
            state={state}
            onRetry={reload}
            isRefreshing={isRefreshing}
            skeleton={<SkeletonCards count={2} />}
            isEmpty={(data) => data.items.length === 0}
            emptyState={
              <EmptyState
                icon="printer"
                title={t('device.none')}
                description={t('device.bridgeNotice')}
                actions={
                  <Button variant="secondary" iconLeft="refresh" onClick={reload}>
                    {tCommon('actions.refresh')}
                  </Button>
                }
              />
            }
          >
            {(data) => (
              <ul className={styles.deviceList}>
                {data.items.map((device) => (
                  <li key={device.id}>
                    <Card>
                      <CardHeader
                        title={device.name}
                        description={
                          device.lastSeenAt
                            ? t('device.lastSeen', {
                                when: formatDateTime(device.lastSeenAt, locale),
                              })
                            : t('device.none')
                        }
                        icon={device.kind === 'bridge' ? 'plug' : 'printer'}
                        headingLevel={3}
                        badge={
                          <StatusPill
                            label={device.online ? tCommon('labels.yes') : tCommon('labels.no')}
                            tone={device.online ? 'success' : 'neutral'}
                          />
                        }
                      />
                      <CardBody>
                        <div className={styles.deviceActions}>
                          <Button
                            variant="secondary"
                            iconLeft="printer"
                            disabled={!device.online}
                            loading={busyDevice === device.id}
                            onClick={() => void startScan(device)}
                          >
                            {t('action.scanNow')}
                          </Button>
                          <Badge tone="neutral" variant="outline">
                            {device.kind}
                          </Badge>
                        </div>
                      </CardBody>
                    </Card>
                  </li>
                ))}
              </ul>
            )}
          </ResourceView>
        </PageSection>

        <PageSection title={t('action.import')} description={t('subtitle')}>
          <div className={styles.importColumn}>
            <FileDropzone onSelect={(files) => queue.enqueue(files)} label={t('dropzone.label')} />
            <p className={styles.meta}>
              {t('dropzone.formats')} · {t('dropzone.maxSize')}
            </p>
            <UploadList
              items={queue.items}
              onCancel={queue.cancel}
              onRetry={queue.retry}
              onRemove={queue.remove}
              title={t('queue.title')}
            />
            <InlineAlert tone="info">{t('security.notice')}</InlineAlert>
          </div>
        </PageSection>
      </div>

      <PageSection title={t('profile.title')}>
        <EmptyState
          icon="layout"
          title={t('profile.title')}
          description={t('device.bridgeNotice')}
        />
      </PageSection>
    </>
  );
}
