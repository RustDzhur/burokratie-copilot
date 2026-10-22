'use client';

import { useState } from 'react';
import { PageSection } from '@/components/app/resource-view';
import { Badge, Card, CardBody, InlineAlert } from '@/components/ui';
import { Input, Textarea } from '@/components/ui/form';
import { Button } from '@/components/ui/button';
import { SkeletonText } from '@/components/ui/feedback';
import { useToast } from '@/components/ui/toast';
import { useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { toApiError } from '@/lib/api/errors';
import { useApiResource } from '@/lib/api/hooks';
import type { CaseWorkspace as Workspace, CompletionCriterion } from '@/lib/api/types';

const KINDS = ['CUSTOM', 'DECISION', 'REPLY_RECEIVED', 'PAYMENT_CONFIRMED'] as const;

/**
 * What the case is for and how "finished" is checked: the goal and the completion criteria. A case counts as finished
 * when its criteria are met or a person closes it deliberately - reading a document or creating a reminder does not finish it.
 */
export function CaseWorkspace({ caseId }: { readonly caseId: string }) {
  const t = useTranslations('caseWorkspace');
  const tErrors = useTranslations('errors');
  const toast = useToast();
  const { state, reload } = useApiResource((signal) => api.cases.workspace(caseId, signal), [caseId]);
  const [goal, setGoal] = useState<string | null>(null);
  const [nextAction, setNextAction] = useState<string | null>(null);
  const [kind, setKind] = useState<(typeof KINDS)[number]>('CUSTOM');
  const [description, setDescription] = useState('');
  const [notes, setNotes] = useState<Record<string, string>>({});
  const [problem, setProblem] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const run = async (work: () => Promise<void>, success?: string): Promise<void> => {
    setBusy(true);
    setProblem(null);
    try {
      await work();
      if (success) toast.success(success);
      reload();
    } catch (error) {
      toast.error(tErrors(toApiError(error).kind));
    } finally {
      setBusy(false);
    }
  };

  const needNote = (key: string, work: (note: string) => Promise<void>): void => {
    const note = (notes[key] ?? '').trim();
    if (note.length === 0) {
      setProblem(t('evidenceRequired'));
      return;
    }
    void run(() => work(note));
  };

  if (state.status === 'loading') {
    return (
      <PageSection title={t('title')}>
        <Card>
          <CardBody>
            <SkeletonText lines={4} />
          </CardBody>
        </Card>
      </PageSection>
    );
  }
  if (state.status === 'error') return null;
  const workspace: Workspace = state.data;

  return (
    <PageSection title={t('title')}>
      <Card>
        <CardBody>
          <Textarea label={t('goal')} rows={2} value={goal ?? workspace.goal ?? ''} onChange={(event) => setGoal(event.target.value)} />
          <Input label={t('nextAction')} value={nextAction ?? workspace.nextAction ?? ''} onChange={(event) => setNextAction(event.target.value)} />
          <Button
            variant="secondary"
            loading={busy}
            onClick={() => void run(() => api.cases.setGoal(caseId, goal ?? workspace.goal ?? '', nextAction ?? workspace.nextAction ?? ''), t('saved'))}
          >
            {t('save')}
          </Button>
        </CardBody>
      </Card>

      <h3>{t('criteria')}</h3>
      {workspace.criteria.length === 0 ? <p>{t('noCriteria')}</p> : null}
      {problem ? <InlineAlert tone="warning">{problem}</InlineAlert> : null}
      <ul style={{ listStyle: 'none', padding: 0, display: 'grid', gap: '0.75rem' }}>
        {workspace.criteria.map((criterion: CompletionCriterion) => (
          <li key={criterion.id} data-testid="criterion">
            <Badge tone={criterion.status === 'MET' ? 'success' : criterion.status === 'WAIVED' ? 'neutral' : 'warning'}>
              {t(`status.${criterion.status}` as 'status.OPEN')}
            </Badge>{' '}
            {criterion.description}
            {criterion.status === 'WAIVED' && criterion.waivedReason ? <div>{t('waivedBecause', { reason: criterion.waivedReason })}</div> : null}
            {criterion.status === 'OPEN' ? (
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: '0.5rem', alignItems: 'end', marginTop: '0.25rem' }}>
                <Input
                  label={t('evidence')}
                  value={notes[criterion.id] ?? ''}
                  onChange={(event) => setNotes((current) => ({ ...current, [criterion.id]: event.target.value }))}
                />
                <Button variant="secondary" loading={busy} onClick={() => needNote(criterion.id, (note) => api.cases.meetCriterion(caseId, criterion.id, note))}>
                  {t('markMet')}
                </Button>
                <Button variant="secondary" loading={busy} onClick={() => needNote(criterion.id, (note) => api.cases.waiveCriterion(caseId, criterion.id, note))}>
                  {t('waive')}
                </Button>
              </div>
            ) : null}
          </li>
        ))}
      </ul>
      <Card>
        <CardBody>
          <label>
            {t('criterionKind')}{' '}
            <select value={kind} onChange={(event) => setKind(event.target.value as (typeof KINDS)[number])}>
              {KINDS.map((value) => (
                <option key={value} value={value}>
                  {t(`kind.${value}` as 'kind.CUSTOM')}
                </option>
              ))}
            </select>
          </label>
          <Input label={t('criterionText')} value={description} onChange={(event) => setDescription(event.target.value)} />
          <Button
            variant="secondary"
            loading={busy}
            disabled={description.trim().length === 0}
            onClick={() => void run(async () => { await api.cases.addCriterion(caseId, { kind, description: description.trim() }); setDescription(''); })}
          >
            {t('addCriterion')}
          </Button>
        </CardBody>
      </Card>
    </PageSection>
  );
}
