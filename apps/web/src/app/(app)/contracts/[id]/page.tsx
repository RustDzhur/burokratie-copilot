'use client';

import Link from 'next/link';
import { useParams } from 'next/navigation';
import { useState } from 'react';
import { RelationshipList } from '@/components/app/relationship-list';
import { PageHeader, PageSection, ResourceView } from '@/components/app/resource-view';
import { Card, CardBody, Button } from '@/components/ui';
import { Select } from '@/components/ui/form';
import { buttonClass } from '@/components/ui/button-class';
import { SkeletonText } from '@/components/ui/feedback';
import { useToast } from '@/components/ui/toast';
import { formatDate, formatDateTime } from '@/i18n/format';
import { useLocale, useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { useApiResource } from '@/lib/api/hooks';
import { toApiError } from '@/lib/api/errors';

/** One contract: terms, obligations, the documents linked to it, and its timeline. */
export default function ContractPage() {
  const { id } = useParams<{ id: string }>();
  const t = useTranslations('entityMemory');
  const tContracts = useTranslations('contracts');
  const tErrors = useTranslations('errors');
  const locale = useLocale();
  const toast = useToast();
  const { state, reload, isRefreshing } = useApiResource((signal) => api.memory.contract(id, signal), [id]);
  const relationships = useApiResource((signal) => api.memory.contractRelationships(id, signal), [id]);
  const timeline = useApiResource((signal) => api.memory.contractTimeline(id, signal), [id]);
  const versions = useApiResource((signal) => api.contracts.versions(id, signal), [id]);
  const contractTemplates = useApiResource(
    (signal) => api.templates.list({ size: 100, type: 'CONTRACT' }, signal),
    [],
  );

  const [templateId, setTemplateId] = useState('');
  const [generating, setGenerating] = useState(false);

  const generate = async (): Promise<void> => {
    if (!templateId) return;
    setGenerating(true);
    try {
      await api.contracts.generate(id, templateId);
      toast.success(tContracts('document.generated'));
      await versions.reload();
    } catch (error) {
      toast.error(tErrors(toApiError(error).kind));
    } finally {
      setGenerating(false);
    }
  };

  const templates =
    contractTemplates.state.status === 'ready' ? contractTemplates.state.data.items : [];

  return (
    <>
      <PageHeader
        title={state.status === 'ready' ? state.data.title : t('back')}
        subtitle={state.status === 'ready' ? [state.data.status, state.data.partnerName].filter(Boolean).join(' · ') : undefined}
        actions={<Link href="/contracts" className={buttonClass({ variant: 'secondary' })}>{t('back')}</Link>}
      />
      <ResourceView
        state={state}
        onRetry={reload}
        isRefreshing={isRefreshing}
        isEmpty={() => false}
        emptyState={null}
        skeleton={<Card><CardBody><SkeletonText lines={5} /></CardBody></Card>}
      >
        {(contract) => (
          <>
            <Card>
              <CardBody>
                <p>{t('period', { from: contract.startDate ? formatDate(contract.startDate, locale) : '—', to: contract.endDate ? formatDate(contract.endDate, locale) : '—' })}</p>
                {contract.cost ? <p>{t('cost')}: {contract.cost}</p> : null}
              </CardBody>
            </Card>
            <PageSection title={tContracts('document.title')}>
              <div style={{ display: 'flex', gap: '0.75rem', flexWrap: 'wrap', alignItems: 'end' }}>
                <Select
                  label={tContracts('document.template')}
                  value={templateId}
                  onChange={(event) => setTemplateId(event.target.value)}
                  options={[
                    { value: '', label: tContracts('document.chooseTemplate') },
                    ...templates.map((template) => ({ value: template.id, label: template.name })),
                  ]}
                  style={{ minWidth: '18rem' }}
                />
                <Button
                  variant="primary"
                  loading={generating}
                  disabled={templateId === '' || templates.length === 0}
                  onClick={() => void generate()}
                >
                  {tContracts('document.generate')}
                </Button>
              </div>
              {templates.length === 0 ? <p>{tContracts('document.noTemplates')}</p> : null}
            </PageSection>
            <PageSection title={tContracts('versions.title')}>
              {versions.state.status === 'ready' && versions.state.data.length > 0 ? (
                <ul style={{ listStyle: 'none', padding: 0 }}>
                  {versions.state.data.map((version) => (
                    <li key={version.id} style={{ marginBottom: '0.5rem' }}>
                      <strong>{version.versionLabel}</strong>
                      {version.current ? ` · ${tContracts('versions.current')}` : null}
                      {version.effectiveFrom ? ` · ${formatDate(version.effectiveFrom, locale)}` : null}
                      {version.changeNote ? ` · ${version.changeNote}` : null}
                      {version.documentId ? (
                        <>
                          {' · '}
                          <Link href={api.contracts.versionDownloadUrl(version.id)}>
                            {tContracts('versions.download')}
                          </Link>
                        </>
                      ) : null}
                    </li>
                  ))}
                </ul>
              ) : (
                <p>{tContracts('versions.empty')}</p>
              )}
            </PageSection>
            <PageSection title={t('obligations')}>
              {contract.obligations.length === 0 ? <p>{t('noObligations')}</p> : (
                <ul>{contract.obligations.map((o) => (
                  <li key={o.id}>{o.title} · {o.dueDate ? formatDate(o.dueDate, locale) : '—'} · {o.status}</li>
                ))}</ul>
              )}
            </PageSection>
            <PageSection title={t('relationships')}>
              <RelationshipList items={relationships.state.status === 'ready' ? relationships.state.data : []} onChanged={relationships.reload} />
            </PageSection>
            <PageSection title={t('timeline')}>
              {timeline.state.status === 'ready' && timeline.state.data.length > 0 ? (
                <ul style={{ listStyle: 'none', padding: 0 }}>{timeline.state.data.map((e) => (
                  <li key={`${e.source}-${e.id}`}><strong>{formatDateTime(e.eventTime, locale)}</strong> — {e.title}</li>
                ))}</ul>
              ) : <p>{t('noTimeline')}</p>}
            </PageSection>
          </>
        )}
      </ResourceView>
    </>
  );
}
