'use client';

import Link from 'next/link';
import { useParams } from 'next/navigation';
import { useState } from 'react';
import { RadarStatusBadge, SeverityBadge } from '@/components/app/radar-badges';
import { PageHeader, PageSection, ResourceView } from '@/components/app/resource-view';
import { Card, CardBody, EmptyState } from '@/components/ui';
import { Input, Textarea } from '@/components/ui/form';
import { Button } from '@/components/ui/button';
import { buttonClass } from '@/components/ui/button-class';
import { SkeletonText } from '@/components/ui/feedback';
import { useToast } from '@/components/ui/toast';
import { formatDate, formatDateTime } from '@/i18n/format';
import { useLocale, useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { toApiError } from '@/lib/api/errors';
import { useApiResource } from '@/lib/api/hooks';
import type { RadarAction, RadarStatus } from '@/lib/api/types';
import { useSession } from '@/lib/auth/session-context';

/** One finding: why it exists, what it rests on, what happened to it, and the decisions the backend allows now. */
export default function RadarFindingPage() {
  const { id } = useParams<{ id: string }>();
  const t = useTranslations('radar');
  const tErrors = useTranslations('errors');
  const locale = useLocale();
  const toast = useToast();
  const session = useSession();
  const { state, reload, isRefreshing } = useApiResource((signal) => api.radar.detail(id, signal), [id]);
  const [reason, setReason] = useState('');
  const [until, setUntil] = useState('');
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);

  const act = async (action: RadarAction): Promise<void> => {
    setProblem(null);
    setBusy(true);
    try {
      await api.radar.act(id, action);
      toast.success(t('done'));
      setReason('');
      reload();
    } catch (error) {
      toast.error(tErrors(toApiError(error).kind));
    } finally {
      setBusy(false);
    }
  };

  const explain = async (): Promise<void> => {
    setBusy(true);
    try {
      const result = await api.radar.explain(id);
      if (result.kind === 'EXPLAINED') toast.success(t('explain.EXPLAINED'));
      else toast.info(t(`explain.${result.kind}` as 'explain.DISABLED'));
      reload();
    } catch (error) {
      toast.error(tErrors(toApiError(error).kind));
    } finally {
      setBusy(false);
    }
  };

  const needReason = (action: RadarAction): void => {
    if (reason.trim().length === 0) {
      setProblem(t('reasonRequired'));
      return;
    }
    void act(action);
  };

  const snooze = (): void => {
    const date = until ? new Date(`${until}T09:00:00`) : null;
    if (!date || date.getTime() <= Date.now()) {
      setProblem(t('snoozeRequired'));
      return;
    }
    void act({ kind: 'snooze', until: date.toISOString(), reason: reason.trim() || undefined });
  };

  return (
    <>
      <PageHeader
        title={state.status === 'ready' ? state.data.title : t('detail.back')}
        subtitle={state.status === 'ready' ? t('detail.rule', { rule: state.data.ruleId, version: state.data.ruleVersion }) : undefined}
        actions={
          <Link href="/radar" className={buttonClass({ variant: 'secondary' })}>
            {t('detail.back')}
          </Link>
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
        {(finding) => {
          const can = (status: RadarStatus): boolean => finding.allowedStatuses.includes(status);
          return (
            <>
              <Card>
                <CardBody>
                  <p>
                    <SeverityBadge severity={finding.severity} /> <RadarStatusBadge status={finding.status} />
                    {finding.confidence !== null ? <small> · {t('detail.confidence', { value: Math.round(finding.confidence * 100) })}</small> : null}
                  </p>
                  <p>{t('detail.advisory')}</p>
                  <h2>{t('detail.why')}</h2>
                  {finding.description ? <p style={{ whiteSpace: 'pre-wrap' }}>{finding.description}</p> : null}
                  {finding.explanation ? (
                    <>
                      <h3>{t('detail.explanation')}</h3>
                      <p style={{ whiteSpace: 'pre-wrap' }}>{finding.explanation}</p>
                    </>
                  ) : null}
                  {finding.snoozedUntil && finding.status === 'SNOOZED' ? <p>{t('detail.snoozedUntil', { date: formatDate(finding.snoozedUntil, locale) })}</p> : null}
                  {finding.resolutionReason ? <p>{t('detail.resolution', { reason: finding.resolutionReason })}</p> : null}
                  {finding.caseId ? (
                    <p>
                      <Link href={`/cases/${finding.caseId}`}>{t('detail.openCase')}</Link>
                    </p>
                  ) : null}
                </CardBody>
              </Card>

              <PageSection title={t('detail.evidence')}>
                {finding.evidence.length === 0 ? (
                  <EmptyState icon="file-text" title={t('detail.noEvidence')} description="" />
                ) : (
                  <ul style={{ listStyle: 'none', padding: 0, display: 'grid', gap: '0.75rem' }}>
                    {finding.evidence.map((item) => (
                      <li key={item.id} data-testid="evidence">
                        <strong>{t(`detail.role.${item.role}` as 'detail.role.PRIMARY')}</strong>
                        {item.documentName ? <> · {item.documentName}</> : null}
                        {item.page ? <>, {t('detail.page', { page: item.page })}</> : null}
                        {item.documentId ? (
                          <>
                            {' · '}
                            <Link href={`/documents/${item.documentId}`}>{t('detail.openDocument')}</Link>
                          </>
                        ) : null}
                        {item.snippet ? <blockquote style={{ margin: '0.25rem 0 0' }}>{item.snippet}</blockquote> : null}
                      </li>
                    ))}
                  </ul>
                )}
              </PageSection>

              {finding.allowedStatuses.length > 0 || session ? (
                <PageSection title={t('detail.decide')}>
                  <Card>
                    <CardBody>
                      <Textarea
                        label={t('detail.reasonLabel')}
                        value={reason}
                        rows={3}
                        onChange={(event) => setReason(event.target.value)}
                        error={problem ?? undefined}
                      />
                      {can('SNOOZED') ? (
                        <Input label={t('detail.snoozeLabel')} type="date" value={until} onChange={(event) => setUntil(event.target.value)} />
                      ) : null}
                      <div style={{ display: 'flex', flexWrap: 'wrap', gap: '0.5rem', marginTop: '0.75rem' }}>
                        {can('UNDER_REVIEW') ? <Button variant="secondary" loading={busy} onClick={() => void act({ kind: 'review' })}>{t('action.review')}</Button> : null}
                        {can('CONFIRMED') ? <Button loading={busy} onClick={() => void act({ kind: 'confirm', reason: reason.trim() || undefined })}>{t('action.confirm')}</Button> : null}
                        {can('DISMISSED') ? <Button variant="secondary" loading={busy} onClick={() => needReason({ kind: 'dismiss', reason: reason.trim() })}>{t('action.dismiss')}</Button> : null}
                        {can('RESOLVED') ? <Button variant="secondary" loading={busy} onClick={() => needReason({ kind: 'resolve', reason: reason.trim() })}>{t('action.resolve')}</Button> : null}
                        {can('SNOOZED') ? <Button variant="secondary" loading={busy} onClick={snooze}>{t('action.snooze')}</Button> : null}
                        {can('REOPENED') ? <Button variant="secondary" loading={busy} onClick={() => void act({ kind: 'reopen', reason: reason.trim() || undefined })}>{t('action.reopen')}</Button> : null}
                        {session ? <Button variant="secondary" loading={busy} onClick={() => void act({ kind: 'assign', userId: session.id })}>{t('action.assignMe')}</Button> : null}
                        {finding.caseId ? null : <Button variant="secondary" loading={busy} onClick={() => void act({ kind: 'create-case' })}>{t('action.createCase')}</Button>}
                        <Button variant="secondary" loading={busy} onClick={() => void act({ kind: 're-evaluate' })}>{t('action.reEvaluate')}</Button>
                        <Button variant="secondary" loading={busy} onClick={() => void explain()}>{t('action.explain')}</Button>
                      </div>
                    </CardBody>
                  </Card>
                </PageSection>
              ) : null}

              <PageSection title={t('detail.history')}>
                {finding.history.length === 0 ? (
                  <p>{t('detail.noHistory')}</p>
                ) : (
                  <ul style={{ listStyle: 'none', padding: 0, display: 'grid', gap: '0.5rem' }}>
                    {finding.history.map((event) => (
                      <li key={event.id}>
                        <strong>{formatDateTime(event.createdAt, locale)}</strong> — {t(`event.${event.eventType}` as 'event.DETECTED')}
                        {event.fromStatus && event.toStatus ? ` (${t(`status.${event.fromStatus}` as 'status.NEW')} → ${t(`status.${event.toStatus}` as 'status.NEW')})` : null}
                        {event.reason ? `: ${event.reason}` : null}
                      </li>
                    ))}
                  </ul>
                )}
              </PageSection>
            </>
          );
        }}
      </ResourceView>
    </>
  );
}
