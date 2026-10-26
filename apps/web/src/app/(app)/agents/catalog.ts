import type { TFunction } from '@/i18n/provider';
import type { StatusTone } from '@/lib/format/status';

/**
 * Shared vocabulary of the agent screens (docs/agents/RUNTIME.md): the eight closed
 * categories, their badge tones, and how machine values are shown. An unknown category
 * or trigger degrades to its machine name instead of blanking a card.
 */

export const AGENT_CATEGORIES = [
  'documents',
  'communication',
  'finance',
  'processes',
  'compliance',
  'analysis',
  'crm',
  'integration',
] as const;

export type AgentCategory = (typeof AGENT_CATEGORIES)[number];

const CATEGORY_TONES: Readonly<Record<AgentCategory, StatusTone>> = {
  documents: 'info',
  communication: 'accent',
  finance: 'success',
  processes: 'info',
  compliance: 'warning',
  analysis: 'neutral',
  crm: 'success',
  integration: 'accent',
};

export function agentCategoryTone(category: string): StatusTone {
  return CATEGORY_TONES[category as AgentCategory] ?? 'neutral';
}

export function agentCategoryLabel(t: TFunction<'agents'>, category: string): string {
  return (AGENT_CATEGORIES as readonly string[]).includes(category)
    ? t(`category.${category}` as 'category.documents')
    : category;
}

/** Event triggers keep their machine name; only the manual trigger gets a word. */
export function agentTriggerLabel(t: TFunction<'agents'>, trigger: string): string {
  return trigger === 'manual' ? t('card.trigger.manual') : trigger;
}
