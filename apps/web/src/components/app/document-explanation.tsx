'use client';

import { useCallback, useEffect, useState } from 'react';
import { Badge } from '@/components/ui';
import { Button } from '@/components/ui/button';
import { useToast } from '@/components/ui/toast';
import { useLocale, useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { toApiError } from '@/lib/api/errors';
import type { ClaimType, ExplanationOutcome, StoredExplanation } from '@/lib/api/types';

type View = 'explanation' | 'translation' | 'simple';
const LANGUAGES = ['de', 'en', 'ru'] as const;

const TONE: Record<ClaimType, 'success' | 'neutral' | 'info' | 'warning' | 'danger'> = {
  SOURCE_FACT: 'success',
  AI_INTERPRETATION: 'info',
  USER_CONFIRMED: 'success',
  UNKNOWN: 'neutral',
  REQUIRES_EXPERT_REVIEW: 'warning',
};

/**
 * The explanation of a document and its translation, shown beside the original and never instead of it.
 * Every claim names its type in words (not by colour alone) and a fact names the page of the quoted passage.
 */
export function DocumentExplanation({ documentId }: { readonly documentId: string }) {
  const t = useTranslations('explanation');
  const tErrors = useTranslations('errors');
  const toast = useToast();
  const locale = useLocale();
  const [view, setView] = useState<View>('explanation');
  const [language, setLanguage] = useState<string>(LANGUAGES.includes(locale as 'de') ? locale : 'de');
  const [stored, setStored] = useState<StoredExplanation | null>(null);
  const [loaded, setLoaded] = useState(false);
  const [busy, setBusy] = useState(false);
  const [outcome, setOutcome] = useState<ExplanationOutcome | null>(null);

  const load = useCallback(async (): Promise<void> => {
    setLoaded(false);
    setOutcome(null);
    try {
      setStored(
        view === 'explanation'
          ? await api.documents.explanation(documentId, language)
          : await api.documents.translation(documentId, language, view === 'simple'),
      );
    } catch (error) {
      toast.error(tErrors(toApiError(error).kind));
    } finally {
      setLoaded(true);
    }
  }, [documentId, language, view, toast, tErrors]);

  useEffect(() => {
    void load();
  }, [load]);

  const generate = async (): Promise<void> => {
    setBusy(true);
    try {
      const result =
        view === 'explanation'
          ? await api.documents.explain(documentId, language)
          : await api.documents.translate(documentId, language, view === 'simple');
      setOutcome(result);
      if (result.kind === 'EXPLAINED' || result.kind === 'TRANSLATED') await load();
    } catch (error) {
      toast.error(tErrors(toApiError(error).kind));
    } finally {
      setBusy(false);
    }
  };

  const content = stored?.content;
  return (
    <section aria-label={t('title')} data-testid="document-explanation">
      <div role="group" aria-label={t('view')} style={{ display: 'flex', gap: '0.5rem', flexWrap: 'wrap', marginBottom: '0.75rem' }}>
        {(['explanation', 'translation', 'simple'] as const).map((item) => (
          <Button key={item} variant={view === item ? 'primary' : 'secondary'} aria-pressed={view === item} onClick={() => setView(item)}>
            {t(`view.${item}` as 'view.explanation')}
          </Button>
        ))}
        <label>
          {t('language')}{' '}
          <select value={language} onChange={(event) => setLanguage(event.target.value)}>
            {LANGUAGES.map((code) => (
              <option key={code} value={code}>
                {t(`lang.${code}` as 'lang.de')}
              </option>
            ))}
          </select>
        </label>
      </div>

      <p role="note">{t('notTheOriginal')}</p>

      <div aria-live="polite">
        {outcome && outcome.kind !== 'EXPLAINED' && outcome.kind !== 'TRANSLATED' ? (
          <p role="alert" data-testid="explanation-outcome">
            {t(`outcome.${outcome.kind}` as 'outcome.REJECTED')}
            {outcome.reason ? ` ${outcome.reason}` : ''}
          </p>
        ) : null}
      </div>

      {!loaded ? <p>{t('loading')}</p> : null}
      {loaded && !content ? <p>{t('none')}</p> : null}

      {content ? (
        <article>
          {stored && !stored.current ? <p role="status">{t('outdated')}</p> : null}
          {content.hiddenInstructionRemoved ? <p role="status">{t('hiddenInstruction')}</p> : null}
          {content.blocks
            ? content.blocks.map((block) => (
                <section key={block.key} aria-labelledby={`block-${block.key}`}>
                  <h3 id={`block-${block.key}`}>{t(`block.${block.key}` as 'block.WHAT_IS_IT')}</h3>
                  <ul style={{ listStyle: 'none', padding: 0, display: 'grid', gap: '0.5rem' }}>
                    {block.claims.map((claim, index) => (
                      <li key={index} data-testid="claim">
                        <Badge tone={TONE[claim.type]}>{t(`type.${claim.type}` as 'type.UNKNOWN')}</Badge> {claim.text}
                        {claim.quote ? (
                          <blockquote lang="de" style={{ margin: '0.25rem 0 0 1rem' }}>
                            „{claim.quote}“{' '}
                            {claim.page ? (
                              <a href={`/api/v1/documents/${documentId}/content#page=${claim.page}`} target="_blank" rel="noreferrer">
                                {t('openPage', { page: claim.page })}
                              </a>
                            ) : null}
                          </blockquote>
                        ) : null}
                      </li>
                    ))}
                  </ul>
                </section>
              ))
            : null}
          {content.text ? (
            <div>
              <Badge tone="warning">{t('machineTranslation')}</Badge>
              <p lang={content.language} style={{ whiteSpace: 'pre-wrap' }}>
                {content.text}
              </p>
            </div>
          ) : null}
          <p role="note">
            <small>{content.disclaimer}</small>
          </p>
        </article>
      ) : null}

      <Button variant="secondary" loading={busy} onClick={() => void generate()}>
        {content ? t('again') : t('generate')}
      </Button>
    </section>
  );
}
