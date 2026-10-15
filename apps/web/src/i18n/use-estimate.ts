import { formatPercent } from './format';
import { useLocale, useTranslations } from './provider';

/**
 * Turns a confidence of a model into text that says what it is. A model's confidence is its own estimate, not a probability,
 * so a percentage is never shown alone (UI-AI-01): it always carries the label.
 */
export function useEstimateText(): (value: number) => string {
  const locale = useLocale();
  const t = useTranslations('common');
  return (value) => `${formatPercent(value, locale)} · ${t('estimate.label')}`;
}
