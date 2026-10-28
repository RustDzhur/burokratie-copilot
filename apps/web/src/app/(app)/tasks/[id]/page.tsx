'use client';

import Link from 'next/link';
import { useParams } from 'next/navigation';
import { useState } from 'react';
import { PageHeader, PageSection, ResourceView } from '@/components/app/resource-view';
import { Card, CardBody, StatusPill } from '@/components/ui';
import { Button } from '@/components/ui/button';
import { InlineAlert } from '@/components/ui/feedback';
import { Checkbox, Textarea } from '@/components/ui/form';
import { useToast } from '@/components/ui/toast';
import { formatDate } from '@/i18n/format';
import { useLocale, useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { ApiError, toApiError } from '@/lib/api/errors';
import { useApiResource } from '@/lib/api/hooks';
import { useStatusLabels } from '@/lib/format/status';

function uiStatus(task: { readonly status: string; readonly finished: boolean }): 'todo' | 'doing' | 'blocked' | 'done' {
  if (task.finished) return 'done';
  if (task.status === 'NEW') return 'todo';
  return task.status === 'BLOCKED' ? 'blocked' : 'doing';
}

/**
 * One task: what finishes it, the checklist to tick and the document it came from. Finishing says why it is refused
 * when it is, in the server's words, instead of a general "conflicts with the current state".
 */
export default function TaskPage() {
  const { id } = useParams<{ id: string }>();
  const t = useTranslations('tasks');
  const tErrors = useTranslations('errors');
  const locale = useLocale();
  const toast = useToast();
  const statusLabel = useStatusLabels('tasks');
  const { state, reload, isRefreshing } = useApiResource((signal) => api.tasks.detail(id, signal), [id]);
  const [force, setForce] = useState(false);
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);

  const tick = async (itemId: string, done: boolean): Promise<void> => {
    setProblem(null);
    try {
      await api.tasks.tick(itemId, done);
      reload();
    } catch (error) {
      toast.error(tErrors(toApiError(error).kind));
    }
  };

  const complete = async (): Promise<void> => {
    setProblem(null);
    if (force && note.trim().length === 0) {
      setProblem(t('detail.noteRequired'));
      return;
    }
    setBusy(true);
    try {
      await api.tasks.complete(id, force ? { note: note.trim(), acknowledgeOpenItems: true } : undefined);
      toast.success(t('detail.done'));
      reload();
    } catch (error) {
      const failure = toApiError(error);
      // The server names what is missing; showing only the kind would hide the one thing the person can act on.
      setProblem(failure instanceof ApiError && failure.status === 409 && failure.message ? failure.message : tErrors(failure.kind));
    } finally {
      setBusy(false);
    }
  };

  return (
    <ResourceView state={state} onRetry={reload} isRefreshing={isRefreshing} emptyState={null}>
      {(task) => {
        const openRequired = task.checklist.filter((item) => item.required && !item.done).length;
        return (
          <>
            <PageHeader
              title={task.title}
              subtitle={task.dueAt ? formatDate(task.dueAt, locale) : undefined}
              meta={<StatusPill label={statusLabel(uiStatus(task))} status={uiStatus(task)} />}
            />
            <PageSection title={t('detail.criteria')}>
              <Card>
                <CardBody>
                  <p>{task.completionCriteria ?? t('detail.noCriteria')}</p>
                  {task.description ? <p>{task.description}</p> : null}
                  <p style={{ display: 'flex', gap: '1rem', flexWrap: 'wrap' }}>
                    {task.sourceDocumentId ? <Link href={`/documents/${task.sourceDocumentId}`}>{t('detail.document')}</Link> : null}
                    {task.caseId ? <Link href={`/cases/${task.caseId}`}>{t('detail.case', { reference: task.caseReference ?? '' })}</Link> : null}
                    <Link href="/tasks">{t('detail.back')}</Link>
                  </p>
                </CardBody>
              </Card>
            </PageSection>
            <PageSection title={t('detail.checklist')}>
              <Card>
                <CardBody>
                  {task.checklist.length === 0 ? (
                    <p>{t('detail.noChecklist')}</p>
                  ) : (
                    <ul style={{ listStyle: 'none', padding: 0, display: 'grid', gap: '0.5rem' }}>
                      {task.checklist.map((item) => (
                        <li key={item.id}>
                          <Checkbox
                            label={item.required ? `${item.label} (${t('detail.required')})` : item.label}
                            checked={item.done}
                            disabled={task.finished}
                            onChange={(event) => void tick(item.id, event.target.checked)}
                          />
                        </li>
                      ))}
                    </ul>
                  )}
                </CardBody>
              </Card>
            </PageSection>
            {task.finished ? (
              <InlineAlert tone="success">{t('detail.finished')}</InlineAlert>
            ) : (
              <PageSection title={t('detail.complete')}>
                <Card>
                  <CardBody>
                    {task.requiresApproval && !task.approved ? <InlineAlert tone="warning">{t('detail.needsApproval')}</InlineAlert> : null}
                    {openRequired > 0 ? (
                      <>
                        <p>{t('detail.openItems', { count: openRequired })}</p>
                        <Checkbox label={t('detail.force')} checked={force} onChange={(event) => setForce(event.target.checked)} />
                        {force ? <Textarea label={t('detail.note')} value={note} onChange={(event) => setNote(event.target.value)} /> : null}
                      </>
                    ) : null}
                    {problem ? <InlineAlert tone="danger">{problem}</InlineAlert> : null}
                    <Button loading={busy} onClick={() => void complete()}>
                      {t('detail.complete')}
                    </Button>
                  </CardBody>
                </Card>
              </PageSection>
            )}
          </>
        );
      }}
    </ResourceView>
  );
}
