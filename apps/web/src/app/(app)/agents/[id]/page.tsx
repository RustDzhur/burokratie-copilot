'use client';

import Link from 'next/link';
import { useParams } from 'next/navigation';
import { useState } from 'react';
import { PageHeader } from '@/components/app/resource-view';
import { Badge, Card, CardBody, CardHeader, EmptyState } from '@/components/ui';
import { ErrorState, InlineAlert, SkeletonText } from '@/components/ui/feedback';
import { Input, Select } from '@/components/ui/form';
import { buttonClass } from '@/components/ui/button-class';
import { formatDateTime } from '@/i18n/format';
import { useLocale, useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { toApiError } from '@/lib/api/errors';
import { useApiResource } from '@/lib/api/hooks';
import type { AgentRunResponse, AgentRunRow } from '@/lib/api/types';
import { agentCategoryLabel, agentCategoryTone, agentTriggerLabel } from '../catalog';
import styles from '../agents.module.css';

/** An input of the manifest: the value is a type hint, e.g. "uuid (optional)". */
function inputIsBoolean(hint: string): boolean {
  return hint.trim().toLowerCase().startsWith('boolean');
}

function inputIsOptional(hint: string): boolean {
  return /optional/i.test(hint);
}

function AgentInputField({
  name,
  hint,
  value,
  onChange,
}: {
  readonly name: string;
  readonly hint: string;
  readonly value: string;
  readonly onChange: (value: string) => void;
}) {
  const t = useTranslations('agents');
  const label = inputIsOptional(hint) ? `${name} (${t('input.optional')})` : name;
  if (inputIsBoolean(hint)) {
    return (
      <Select
        label={label}
        hint={hint}
        value={value}
        onChange={(event) => onChange(event.target.value)}
      >
        <option value="">—</option>
        <option value="true">{t('input.yes')}</option>
        <option value="false">{t('input.no')}</option>
      </Select>
    );
  }
  return (
    <Input
      label={label}
      hint={hint}
      value={value}
      onChange={(event) => onChange(event.target.value)}
    />
  );
}

/** One journal row: when it ran, why, and the honest answer it gave. */
function JournalRow({ run }: { readonly run: AgentRunRow }) {
  const t = useTranslations('agents');
  const locale = useLocale();
  const inputs = Object.entries(run.inputs);
  return (
    <li className={styles.journalRow} data-testid="agent-run">
      <div className={styles.journalMeta}>
        <time dateTime={run.startedAt}>{formatDateTime(run.startedAt, locale)}</time>
        <Badge tone="neutral" variant="outline">
          {agentTriggerLabel(t, run.trigger)}
        </Badge>
        <Badge tone={run.ok ? 'success' : 'danger'}>
          {run.ok ? t('result.ok') : t('result.notOk')}
        </Badge>
      </div>
      <p className={styles.journalSummary}>{run.summary}</p>
      {inputs.length > 0 ? (
        <p className={styles.journalMeta}>
          {t('journal.inputs')}: {inputs.map(([key, value]) => `${key}=${value}`).join(', ')}
        </p>
      ) : null}
    </li>
  );
}

/** One agent: what it promises, running it by hand, and its journal. */
export default function AgentDetailPage() {
  const { id } = useParams<{ id: string }>();
  const t = useTranslations('agents');
  const tNav = useTranslations('nav');
  const tErrors = useTranslations('errors');
  const { state, reload, isRefreshing } = useApiResource(
    (signal) => api.agents.detail(id, signal),
    [id],
  );

  const [trigger, setTrigger] = useState('manual');
  const [values, setValues] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const [runError, setRunError] = useState<string | null>(null);
  const [result, setResult] = useState<AgentRunResponse | null>(null);

  const run = async (): Promise<void> => {
    setBusy(true);
    setRunError(null);
    setResult(null);
    try {
      const inputs: Record<string, string> = {};
      for (const [key, value] of Object.entries(values)) {
        const trimmed = (value ?? '').trim();
        if (trimmed !== '') inputs[key] = trimmed;
      }
      const response = await api.agents.run(id, { trigger, inputs });
      setResult(response);
      reload(); // journal and run counter change after every run
    } catch (error) {
      setRunError(tErrors(toApiError(error).kind));
    } finally {
      setBusy(false);
    }
  };

  if (state.status === 'loading') {
    return (
      <>
        <PageHeader
          title={tNav('items.agents')}
          actions={
            <Link href="/agents" className={buttonClass({ variant: 'secondary' })}>
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
          title={tNav('items.agents')}
          actions={
            <Link href="/agents" className={buttonClass({ variant: 'secondary' })}>
              {t('detail.back')}
            </Link>
          }
        />
        <ErrorState error={state.error} title={title} onRetry={reload} />
      </>
    );
  }

  const { manifest, implemented, runCount, runs } = state.data;
  const triggerOptions = ['manual', ...manifest.triggers.filter((value: string) => value !== 'manual')];
  const inputEntries = Object.entries(manifest.inputs);

  return (
    <>
      <PageHeader
        title={manifest.name}
        subtitle={tNav('items.agents')}
        actions={
          <Link href="/agents" className={buttonClass({ variant: 'secondary' })}>
            {t('detail.back')}
          </Link>
        }
        meta={
          <div className={styles.metaRow}>
            <Badge tone={agentCategoryTone(manifest.category)}>
              {agentCategoryLabel(t, manifest.category)}
            </Badge>
            {implemented ? (
              <span className={styles.livePill} title={t('status.liveHint')}>
                {t('status.live')}
              </span>
            ) : (
              <span className={styles.konzeptPill} title={t('status.konzeptHint')}>
                {t('status.konzept')}
              </span>
            )}
            <span className={styles.pillHint}>
              {runCount > 0 ? t('detail.runCount', { count: runCount }) : t('card.neverRun')}
            </span>
          </div>
        }
      />

      <div className={styles.detailGrid} aria-busy={isRefreshing || undefined}>
        <div className={styles.detailColumn}>
          <Card>
            <CardBody>
              <p className={styles.description}>{manifest.description}</p>
            </CardBody>
          </Card>

          {manifest.functions.length > 0 ? (
            <Card>
              <CardHeader title={t('detail.functions')} headingLevel={2} />
              <CardBody>
                <ul className={styles.sectionList}>
                  {manifest.functions.map((fn: string) => (
                    <li key={fn}>{fn}</li>
                  ))}
                </ul>
              </CardBody>
            </Card>
          ) : null}

          {manifest.triggers.length > 0 ? (
            <Card>
              <CardHeader title={t('detail.triggers')} headingLevel={2} />
              <CardBody>
                <div className={styles.triggerList}>
                  {manifest.triggers.map((value: string) => (
                    <Badge key={value} tone="neutral" variant="outline">
                      {agentTriggerLabel(t, value)}
                    </Badge>
                  ))}
                </div>
              </CardBody>
            </Card>
          ) : null}
        </div>

        <div className={styles.detailColumn}>
          <Card>
            <CardHeader
              title={t('run.title')}
              description={t('run.description')}
              headingLevel={2}
            />
            <CardBody>
              <div className={styles.inputsGrid}>
                <Select
                  label={t('run.trigger')}
                  value={trigger}
                  onChange={(event) => setTrigger(event.target.value)}
                >
                  {triggerOptions.map((value: string) => (
                    <option key={value} value={value}>
                      {agentTriggerLabel(t, value)}
                    </option>
                  ))}
                </Select>

                {inputEntries.length > 0 ? (
                  <>
                    <p className={styles.pillHint}>{t('detail.inputsHint')}</p>
                    {inputEntries.map(([name, hint]: [string, unknown]) => (
                      <AgentInputField
                        key={name}
                        name={name}
                        hint={String(hint ?? '')}
                        value={values[name] ?? ''}
                        onChange={(value) =>
                          setValues((current) => ({ ...current, [name]: value }))
                        }
                      />
                    ))}
                  </>
                ) : (
                  <p className={styles.pillHint}>{t('detail.inputsEmpty')}</p>
                )}

                {!implemented ? (
                  <InlineAlert tone="warning">{t('status.konzeptHint')}</InlineAlert>
                ) : null}

                <button
                  type="button"
                  className={styles.runButton}
                  onClick={() => void run()}
                  disabled={busy || !implemented}
                  aria-busy={busy}
                  data-testid="agent-run-button"
                >
                  {busy ? t('run.busy') : t('run.button')}
                </button>

                {runError ? <InlineAlert tone="danger">{runError}</InlineAlert> : null}

                {result ? (
                  <div
                    className={styles.resultBox}
                    role="status"
                    aria-live="polite"
                    data-testid="run-result"
                  >
                    <div className={styles.resultHead}>
                      <strong>{t('result.title')}</strong>
                      <Badge tone={result.ok ? 'success' : 'danger'}>
                        {result.ok ? t('result.ok') : t('result.notOk')}
                      </Badge>
                    </div>
                    <p className={styles.journalSummary}>{result.summary}</p>
                    <p className={styles.cardSectionLabel}>{t('result.produced')}</p>
                    {result.produced.length === 0 ? (
                      <p className={styles.journalSummary}>{t('result.producedEmpty')}</p>
                    ) : (
                      <ul className={styles.producedList}>
                        {result.produced.map((item: { kind: string; ref: string; label: string }, index) => (
                          <li key={`${item.kind}-${item.ref}-${index}`}>
                            <span className={styles.producedKind}>{item.kind}</span>
                            {item.ref ? <code>{item.ref}</code> : null}
                            {item.label ? ` — ${item.label}` : null}
                          </li>
                        ))}
                      </ul>
                    )}
                  </div>
                ) : null}
              </div>
            </CardBody>
          </Card>

          <Card>
            <CardHeader title={t('journal.title')} headingLevel={2} />
            <CardBody>
              {runs.length === 0 ? (
                <EmptyState icon="clock" size="sm" title={t('journal.empty')} />
              ) : (
                <ol className={styles.journalList}>
                  {runs.map((run: AgentRunRow) => (
                    <JournalRow key={run.id} run={run} />
                  ))}
                </ol>
              )}
            </CardBody>
          </Card>
        </div>
      </div>
    </>
  );
}
