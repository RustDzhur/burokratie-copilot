'use client';

import Link from 'next/link';
import { useParams } from 'next/navigation';
import { Badge, Card, CardBody, CardHeader } from '@/components/ui';
import { buttonClass } from '@/components/ui/button-class';
import { InlineAlert, SkeletonText } from '@/components/ui/feedback';
import { useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { useApiResource } from '@/lib/api/hooks';

/**
 * One generated letter and what stands in the way of sending it (spec §15).
 *
 * <p><strong>Why this page exists.</strong> The readiness check was written, tested and reachable only as a
 * route, and then the list offered a link to a detail page that did not exist - a 404 dressed as a feature.
 * This is that page.</p>
 *
 * <p>It shows the three things the check reports: requisites that are missing, statements nobody stands behind,
 * and the problems in words. "Not ready" without a reason is a red dot, and a person cannot act on a red dot.</p>
 */
export default function GeneratedDocumentPage() {
  const params = useParams<{ id: string }>();
  const t = useTranslations('generatedDocuments');
  const id = params.id;

  const resource = useApiResource((signal) => api.generatedDocuments.readiness(id, signal), [id]);

  const { state } = resource;

  if (state.status === 'loading') {
    return (
      <Card>
        <CardHeader title={t('readiness.title')} />
        <CardBody>
          <SkeletonText lines={3} />
        </CardBody>
      </Card>
    );
  }

  if (state.status === 'error') {
    return <InlineAlert tone="danger" title={t('readiness.error')}>{state.error.message}</InlineAlert>;
  }

  const readiness = state.data;

  return (
    <div className="stack">
      <Link href="/generated-documents" className={buttonClass({ variant: 'ghost', size: 'sm' })}>
        {t('readiness.back')}
      </Link>
      <Card>
        <CardHeader
          title={t('readiness.title')}
          description={t('readiness.description')}
          actions={
            <Badge tone={readiness?.readyToSend ? 'success' : 'warning'}>
              {readiness?.readyToSend ? t('readiness.ready') : t('readiness.notReady')}
            </Badge>
          }
        />
        <CardBody>
          {readiness?.problems.length ? (
            <ul className="stack-tight">
              {readiness.problems.map((problem) => (
                <li key={problem}>
                  <InlineAlert tone="warning" title={t('readiness.problem')}>{problem}</InlineAlert>
                </li>
              ))}
            </ul>
          ) : (
            <InlineAlert tone="success" title={t('readiness.ready')}>
              {t('readiness.readyHint')}
            </InlineAlert>
          )}
          <a href={api.generatedDocuments.downloadUrl(id)} target="_blank" rel="noopener"
             className={buttonClass({ variant: 'secondary', size: 'sm' })}>
            {t('action.download')}
          </a>
        </CardBody>
      </Card>
    </div>
  );
}
