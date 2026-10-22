import type { MandantRule } from '@/lib/api/types';

/**
 * The rows of the rule list that match what a person typed.
 *
 * <p>The list can grow to hundreds of rules in a busy office, and the question in front of it is usually "why did
 * this client's voucher go there" - so the search covers the client, the raw signal and the readable signal name,
 * not only the stored pattern.</p>
 */
export function filterMandantRules(
  rules: readonly MandantRule[],
  query: string,
  signalLabel: (rule: MandantRule) => string,
): readonly MandantRule[] {
  const needle = query.trim().toLowerCase();
  if (needle === '') return rules;
  return rules.filter((rule) =>
    [rule.muster, rule.mandantName ?? '', signalLabel(rule)].some((value) =>
      value.toLowerCase().includes(needle),
    ),
  );
}

/** Whether a rule was taught by a person or derived from an automatic assignment. */
export function isLearned(rule: MandantRule): boolean {
  return rule.quelle === 'MANUELL';
}
