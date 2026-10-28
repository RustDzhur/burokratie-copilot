'use client';

import { useState } from 'react';
import { PageHeader, PageSection } from '@/components/app/resource-view';
import { Card, CardBody, EmptyState, StatusPill } from '@/components/ui';
import { Button } from '@/components/ui/button';
import { Select, Textarea } from '@/components/ui/form';
import { ErrorState, InlineAlert } from '@/components/ui/feedback';
import { useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { toApiError, type ApiError } from '@/lib/api/errors';
import type { AssistantAnswer } from '@/lib/api/types';
import styles from './assistant.module.css';

type Mode = 'question' | 'summarize' | 'draft';

interface Turn {
  readonly id: string;
  readonly question: string;
  readonly answer: AssistantAnswer;
}

/**
 * AI assistant (§5.2.18, §10.4).
 *
 * Answers are only shown when the backend returns them, always with the sources
 * that were used, and always with the "not legally binding" disclaimer
 * (docs/task.md §1.2). Nothing is generated on the client.
 */
export default function AssistantPage() {
  const t = useTranslations('assistant');
  const tNav = useTranslations('nav');
  const tCommon = useTranslations('common');
  const tErrors = useTranslations('errors');
  const tViewer = useTranslations('documentViewer');

  const [question, setQuestion] = useState('');
  const [mode, setMode] = useState<Mode>('question');
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [turns, setTurns] = useState<readonly Turn[]>([]);

  const ask = async (): Promise<void> => {
    if (question.trim() === '') return;
    setPending(true);
    setError(null);
    try {
      const answer = await api.assistant.ask({ question, mode });
      setTurns((current) => [...current, { id: `${Date.now()}`, question, answer }]);
      setQuestion('');
    } catch (caught) {
      setError(toApiError(caught));
    } finally {
      setPending(false);
    }
  };

  return (
    <>
      <PageHeader
        title={tNav('items.assistant')}
        subtitle={t('subtitle')}
        actions={
          turns.length > 0 ? (
            <Button variant="secondary" iconLeft="refresh" onClick={() => setTurns([])}>
              {t('action.newChat')}
            </Button>
          ) : null
        }
      />

      <InlineAlert tone="warning">{t('disclaimer')}</InlineAlert>

      <PageSection title={t('prompt.label')}>
        <Card>
          <CardBody>
            <div className={styles.form}>
              <Textarea
                label={t('prompt.label')}
                placeholder={t('prompt.placeholder')}
                value={question}
                onChange={(event) => setQuestion(event.target.value)}
                rows={4}
              />
              <div className={styles.controls}>
                <Select
                  label={t('mode.question')}
                  selectSize="sm"
                  value={mode}
                  onChange={(event) => setMode(event.target.value as Mode)}
                  options={[
                    { value: 'question', label: t('mode.question') },
                    { value: 'summarize', label: t('mode.summarize') },
                    { value: 'draft', label: t('mode.draft') },
                  ]}
                />
                <Button
                  variant="primary"
                  iconLeft="sparkles"
                  loading={pending}
                  disabled={question.trim() === ''}
                  onClick={() => void ask()}
                >
                  {t('action.send')}
                </Button>
              </div>
            </div>
          </CardBody>
        </Card>
      </PageSection>

      {error ? <ErrorState error={error} onRetry={() => void ask()} /> : null}

      {pending ? <InlineAlert tone="info">{t('thinking')}</InlineAlert> : null}

      <PageSection title={t('sources.title')}>
        {turns.length === 0 ? (
          <EmptyState
            icon="sparkles"
            title={t('empty.title')}
            description={t('empty.description')}
            actions={
              <div className={styles.suggestions}>
                <Button
                  variant="secondary"
                  size="sm"
                  onClick={() => setQuestion(t('suggestion.deadlines'))}
                >
                  {t('suggestion.deadlines')}
                </Button>
                <Button
                  variant="secondary"
                  size="sm"
                  onClick={() => setQuestion(t('suggestion.invoices'))}
                >
                  {t('suggestion.invoices')}
                </Button>
                <Button
                  variant="secondary"
                  size="sm"
                  onClick={() => setQuestion(t('suggestion.summary'))}
                >
                  {t('suggestion.summary')}
                </Button>
              </div>
            }
          />
        ) : (
          <ul className={styles.turns}>
            {turns.map((turn) => (
              <li key={turn.id}>
                <Card>
                  <CardBody>
                    <p className={styles.question}>{turn.question}</p>
                    <p className={styles.answer}>{turn.answer.answer}</p>
                    <div className={styles.answerMeta}>
                      <StatusPill label={turn.answer.model} tone="info" />
                      {turn.answer.correlationId ? (
                        <span className={styles.correlation}>{turn.answer.correlationId}</span>
                      ) : null}
                    </div>
                    {turn.answer.sources.length > 0 ? (
                      <ul className={styles.sources}>
                        {turn.answer.sources.map((source) => (
                          <li key={`${source.documentId}-${source.page ?? 0}`}>
                            <a href={`/documents/${source.documentId}`}>{source.title}</a>
                            {source.page ? (
                              <span>
                                {' · '}
                                {tViewer('metadata.pages')} {source.page}
                              </span>
                            ) : null}
                          </li>
                        ))}
                      </ul>
                    ) : (
                      <p className={styles.noSources}>{t('sources.empty')}</p>
                    )}
                  </CardBody>
                </Card>
              </li>
            ))}
          </ul>
        )}
      </PageSection>

      <p className={styles.footerNote}>
        {tCommon('app.name')} · {tErrors('unknown')}
      </p>
    </>
  );
}
