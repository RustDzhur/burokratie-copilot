'use client';

import Link from 'next/link';
import { useParams } from 'next/navigation';
import { useState } from 'react';
import { PageHeader } from '@/components/app/resource-view';
import { Badge, Card, CardBody, CardHeader, EmptyState } from '@/components/ui';
import { Button } from '@/components/ui/button';
import { ErrorState, InlineAlert, SkeletonText } from '@/components/ui/feedback';
import { useToast } from '@/components/ui/toast';
import { useLocale, useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { toApiError } from '@/lib/api/errors';
import { useApiResource } from '@/lib/api/hooks';
import type {
  AgentConstellationGraph,
  AgentConstellationStep,
  AgentWorkflowOutcome,
} from '@/lib/api/types';
import { constellationIndustryLabel, constellationText, constellationTimeSaved } from '../catalog';
import styles from '../constellations.module.css';

/** Clipboard with a fallback for browsers that do not expose the async clipboard API. */
async function writeClipboard(text: string): Promise<void> {
  if (typeof navigator !== 'undefined' && navigator.clipboard?.writeText) {
    await navigator.clipboard.writeText(text);
    return;
  }
  const area = document.createElement('textarea');
  area.value = text;
  area.setAttribute('readonly', '');
  area.style.position = 'fixed';
  area.style.opacity = '0';
  document.body.append(area);
  area.select();
  const copied = document.execCommand('copy');
  area.remove();
  if (!copied) throw new Error('copy refused');
}

/** One step of the chain: the agent, what it does, and whether it is already implemented. */
function ConstellationStepRow({ step }: { readonly step: AgentConstellationStep }) {
  const t = useTranslations('constellations');
  return (
    <li className={styles.step}>
      <span className={styles.stepOrder}>{String(step.order).padStart(2, '0')}</span>
      <div className={styles.stepBody}>
        <div className={styles.stepHead}>
          <h3 className={styles.stepTitle}>{step.title}</h3>
          {step.implemented ? (
            <span className={styles.livePill} title={t('status.liveHint')}>
              {t('status.live')}
            </span>
          ) : (
            <span className={styles.konzeptPill} title={t('status.konzeptHint')}>
              {t('status.konzept')}
            </span>
          )}
          {step.optional ? (
            <Badge tone="neutral" variant="outline">
              {t('detail.optional')}
            </Badge>
          ) : null}
        </div>

        <p className={styles.stepPurpose}>{step.purpose}</p>

        <p className={styles.stepAgent}>
          <span className={styles.stepDataLabel}>{t('detail.agent')}:</span>{' '}
          <Link href={`/agents/${encodeURIComponent(step.agentId)}`} className={styles.agentLink}>
            {step.agentName}
          </Link>{' '}
          <code className={styles.agentId}>{step.agentId}</code>
        </p>

        <dl className={styles.stepData}>
          <div>
            <dt>{t('detail.input')}</dt>
            <dd>{step.input}</dd>
          </div>
          <div>
            <dt>{t('detail.output')}</dt>
            <dd>{step.output}</dd>
          </div>
        </dl>
      </div>
    </li>
  );
}

/** One preset: its steps in order, and the graph for a map (copied or downloaded). */
export default function ConstellationDetailPage() {
  const { id } = useParams<{ id: string }>();
  const t = useTranslations('constellations');
  const tNav = useTranslations('nav');
  const tErrors = useTranslations('errors');
  const locale = useLocale();
  const toast = useToast();
  const { state, reload, isRefreshing } = useApiResource(
    (signal) => api.constellations.detail(id, signal),
    [id],
  );

  const [busy, setBusy] = useState<'copy' | 'download' | null>(null);
  const [graph, setGraph] = useState<AgentConstellationGraph | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [run, setRun] = useState<AgentWorkflowOutcome | null>(null);
  const [runBusy, setRunBusy] = useState(false);

  const loadGraph = async (): Promise<AgentConstellationGraph> => {
    if (graph) return graph;
    const next = await api.constellations.instantiate(id);
    setGraph(next);
    return next;
  };

  const startRun = async (): Promise<void> => {
    setRunBusy(true);
    setActionError(null);
    try {
      const workflow = await loadGraph();
      const outcome = await api.workflows.run(workflow.workflowId);
      setRun(outcome);
      toast.success(t('detail.runStatus', { status: outcome.status }));
    } catch (error) {
      const message = tErrors(toApiError(error).kind);
      setActionError(message);
      toast.error(t('detail.runFailed'), message);
    } finally {
      setRunBusy(false);
    }
  };

  const decideRun = async (decision: 'APPROVE' | 'RETRY' | 'SKIP'): Promise<void> => {
    if (!run) return;
    setRunBusy(true);
    setActionError(null);
    try {
      const outcome = await api.workflows.resume(run.runId, decision);
      setRun(outcome);
      toast.success(t('detail.runStatus', { status: outcome.status }));
    } catch (error) {
      const message = tErrors(toApiError(error).kind);
      setActionError(message);
      toast.error(t('detail.runFailed'), message);
    } finally {
      setRunBusy(false);
    }
  };

  const copyJson = async (): Promise<void> => {
    setBusy('copy');
    setActionError(null);
    try {
      const next = await loadGraph();
      await writeClipboard(JSON.stringify(next, null, 2));
      toast.success(t('detail.copyDone'));
    } catch (error) {
      const message = tErrors(toApiError(error).kind);
      setActionError(message);
      toast.error(t('detail.failed'), message);
    } finally {
      setBusy(null);
    }
  };

  const downloadJson = async (): Promise<void> => {
    setBusy('download');
    setActionError(null);
    try {
      const next = await loadGraph();
      const blob = new Blob([JSON.stringify(next, null, 2)], { type: 'application/json' });
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement('a');
      anchor.href = url;
      anchor.download = `${id}.constellation.json`;
      document.body.append(anchor);
      anchor.click();
      anchor.remove();
      URL.revokeObjectURL(url);
      toast.success(t('detail.downloadDone'));
    } catch (error) {
      const message = tErrors(toApiError(error).kind);
      setActionError(message);
      toast.error(t('detail.failed'), message);
    } finally {
      setBusy(null);
    }
  };

  if (state.status === 'loading') {
    return (
      <>
        <PageHeader
          title={tNav('items.constellations')}
          actions={
            <Link href="/constellations" className={styles.backLink}>
              {t('detail.back')}
            </Link>
          }
        />
        <Card>
          <CardBody>
            <SkeletonText lines={8} />
          </CardBody>
        </Card>
      </>
    );
  }

  if (state.status === 'error') {
    const kind = state.error.kind;
    const title =
      kind === 'forbidden'
        ? t('error.forbidden')
        : kind === 'notFound'
          ? t('error.notFound')
          : kind === 'unauthorized'
            ? t('error.unauthorized')
            : undefined;
    return (
      <>
        <PageHeader
          title={tNav('items.constellations')}
          actions={
            <Link href="/constellations" className={styles.backLink}>
              {t('detail.back')}
            </Link>
          }
        />
        <ErrorState error={state.error} title={title} onRetry={reload} />
      </>
    );
  }

  const constellation = state.data;
  const name = constellationText(constellation.name, locale);

  return (
    <>
      <PageHeader
        title={name}
        subtitle={tNav('items.constellations')}
        breadcrumbs={[
          { label: tNav('items.constellations'), href: '/constellations' },
          { label: name },
        ]}
        meta={
          <div className={styles.metaRow}>
            {constellation.industries.map((industry) => (
              <Badge key={industry} tone="neutral" variant="outline">
                {constellationIndustryLabel(t, industry)}
              </Badge>
            ))}
            <span className={styles.metaText}>
              {t('card.steps', { count: constellation.steps.length })}
            </span>
            <span className={styles.metaImplemented}>
              {t('card.implemented', { count: constellation.implementedSteps })}
            </span>
            {constellation.conceptSteps > 0 ? (
              <span className={styles.metaConcept}>
                {t('card.concept', { count: constellation.conceptSteps })}
              </span>
            ) : null}
            <span className={styles.timePill}>
              {constellationTimeSaved(constellation.estimatedTimeSaved, locale, t)}
            </span>
          </div>
        }
        actions={
          <Link href="/constellations" className={styles.backLink}>
            {t('detail.back')}
          </Link>
        }
      />

      <div className={styles.detailGrid} aria-busy={isRefreshing || undefined}>
        <div className={styles.detailColumn}>
          <Card>
            <CardBody>
              <p className={styles.description}>
                {constellationText(constellation.description, locale)}
              </p>
            </CardBody>
          </Card>

          <Card>
            <CardHeader
              title={t('detail.steps')}
              description={t('detail.stepsHint')}
              headingLevel={2}
            />
            <CardBody>
              {constellation.steps.length === 0 ? (
                <EmptyState icon="sparkles" size="sm" title={t('empty')} />
              ) : (
                <ol className={styles.stepList}>
                  {constellation.steps.map((step) => (
                    <ConstellationStepRow key={step.order} step={step} />
                  ))}
                </ol>
              )}
            </CardBody>
          </Card>

          {constellation.outputs.length > 0 ? (
            <Card>
              <CardHeader title={t('detail.outputs')} headingLevel={2} />
              <CardBody>
                <ul className={styles.outputList}>
                  {constellation.outputs.map((output, index) => (
                    <li key={`${index}-${constellationText(output, locale)}`}>
                      {constellationText(output, locale)}
                    </li>
                  ))}
                </ul>
              </CardBody>
            </Card>
          ) : null}
        </div>

        <div className={styles.detailColumn}>
          <Card>
            <CardHeader
              title={t('detail.actions')}
              description={t('detail.actionsHint')}
              headingLevel={2}
            />
            <CardBody>
              <div className={styles.actionButtons}>
                <Button
                  variant="primary"
                  iconLeft="copy"
                  loading={busy === 'copy'}
                  onClick={() => void copyJson()}
                  data-testid="constellation-copy"
                >
                  {t('detail.copy')}
                </Button>
                <Button
                  variant="secondary"
                  iconLeft="download"
                  loading={busy === 'download'}
                  onClick={() => void downloadJson()}
                  data-testid="constellation-download"
                >
                  {t('detail.download')}
                </Button>
              </div>

              <div className={styles.actionButtons} style={{ marginTop: 12 }}>
                <Button
                  variant="secondary"
                  iconLeft="play"
                  loading={runBusy}
                  onClick={() => void startRun()}
                  data-testid="constellation-run"
                >
                  {runBusy ? t('detail.running') : t('detail.run')}
                </Button>
              </div>

              {run ? (
                <div className={styles.warnings}>
                  <p className={styles.cardSectionLabel}>
                    {run.status === 'PAUSED_FOR_HUMAN_REVIEW'
                      ? t('detail.paused')
                      : t('detail.runStatus', { status: run.status })}
                  </p>
                  {run.status === 'PAUSED_FOR_HUMAN_REVIEW' ? (
                    <div className={styles.actionButtons}>
                      <Button variant="primary" loading={runBusy} onClick={() => void decideRun('APPROVE')}>
                        {t('detail.approve')}
                      </Button>
                      <Button variant="secondary" loading={runBusy} onClick={() => void decideRun('RETRY')}>
                        {t('detail.retry')}
                      </Button>
                      <Button variant="ghost" loading={runBusy} onClick={() => void decideRun('SKIP')}>
                        {t('detail.skip')}
                      </Button>
                    </div>
                  ) : null}
                </div>
              ) : null}

              {actionError ? <InlineAlert tone="danger">{actionError}</InlineAlert> : null}

              {graph && graph.warnings.length > 0 ? (
                <div className={styles.warnings}>
                  <p className={styles.cardSectionLabel}>{t('detail.warnings')}</p>
                  <ul className={styles.warningList}>
                    {graph.warnings.map((warning) => (
                      <li key={warning}>{warning}</li>
                    ))}
                  </ul>
                </div>
              ) : null}
            </CardBody>
          </Card>

          {constellation.unknownAgents.length > 0 ? (
            <InlineAlert tone="warning">
              {t('detail.unknownAgents', { agents: constellation.unknownAgents.join(', ') })}
            </InlineAlert>
          ) : null}
        </div>
      </div>
    </>
  );
}
