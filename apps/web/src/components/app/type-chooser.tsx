'use client';

import { useEffect, useMemo } from 'react';
import { Button } from '@/components/ui/button';
import { Select } from '@/components/ui/form';
import { useEstimateText } from '@/i18n/use-estimate';
import { useLocale, useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { useApiResource } from '@/lib/api/hooks';

export interface TypeChoice {
  /** The type the person chose or took over from the suggestion; null while none was. */
  readonly code: string | null;
  /** Whether the document has a type or the person has chosen one, so that confirming means something. */
  readonly ready: boolean;
}

/**
 * The person decides what kind of document this is. The platform's suggestion is offered as a button with its name, never
 * applied by itself; without a type (suggested or chosen) confirming is not possible, because a confirmation that fixes "something"
 * files the document under a wrong drawer.
 */
export function TypeChooser({
  documentId,
  value,
  onChange,
}: {
  readonly documentId: string;
  readonly value: string | null;
  readonly onChange: (choice: TypeChoice) => void;
}) {
  const t = useTranslations('review');
  const tDocs = useTranslations('documents');
  const locale = useLocale();
  const estimate = useEstimateText();
  const flow = useApiResource((signal) => api.documents.pipeline(documentId, signal), [documentId]);
  const catalogue = useApiResource((signal) => api.documents.catalogue(signal), []);

  const hasType = flow.state.status === 'ready' && flow.state.data.documentTypeName !== null;
  useEffect(() => {
    onChange({ code: value, ready: hasType || value !== null });
  }, [hasType, value, onChange]);

  const options = useMemo(() => {
    if (catalogue.state.status !== 'ready') return [];
    return catalogue.state.data
      .map((entry) => ({
        value: entry.code,
        label: `${tDocs(`category.${entry.category}` as 'category.ALL')} · ${entry.names[locale] ?? entry.names.de}`,
      }))
      .sort((a, b) => a.label.localeCompare(b.label, locale));
  }, [catalogue.state, locale, tDocs]);

  if (flow.state.status !== 'ready') return null;
  const suggestion = flow.state.data.suggestedType;
  const typeName = flow.state.data.typeNames?.[locale] ?? flow.state.data.typeNames?.de ?? flow.state.data.documentTypeName;

  return (
    <section aria-labelledby="type-chooser-title" data-testid="type-chooser">
      <h3 id="type-chooser-title">{t('type.title')}</h3>
      {hasType ? <p>{t('type.current', { type: typeName ?? '' })}</p> : <p role="note">{t('type.required')}</p>}
      {!hasType && suggestion ? (
        <p>
          <Button variant={value === suggestion.code ? 'primary' : 'secondary'} aria-pressed={value === suggestion.code} onClick={() => onChange({ code: suggestion.code, ready: true })}>
            {t('type.accept', { type: suggestion.name })}
          </Button>{' '}
          <small>{suggestion.confidence !== null ? estimate(suggestion.confidence) : null}</small>
        </p>
      ) : null}
      <Select label={hasType ? t('type.change') : t('type.pick')} value={value ?? ''} onChange={(event) => onChange({ code: event.target.value || null, ready: hasType || event.target.value !== '' })}>
        <option value="">{hasType ? t('type.keep') : t('type.none')}</option>
        {options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </Select>
    </section>
  );
}
