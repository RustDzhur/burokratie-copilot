import type { Locale } from '@/i18n/config';
import type { TFunction } from '@/i18n/provider';
import type { ConstellationTimeSaved, ConstellationText } from '@/lib/api/types';

/**
 * Shared vocabulary of the constellation screens (docs/agents/CONSOLATIONS.md).
 *
 * The presets carry only German and Russian copy; English falls back to German instead of
 * inventing translations for data the backend owns. Industry tags are free-form in the preset
 * files, so an unknown tag degrades to its machine name — a new preset must not blank a card.
 */

/** Industry tags currently used by the presets; the filter also shows tags outside this list. */
export const CONSTELLATION_INDUSTRIES = [
  'handwerk',
  'services',
  'ecommerce',
  'manufacturing',
  'agency',
  'healthcare',
  'logistics',
  'realestate',
  'retail',
  'it',
  'consulting',
  'gastronomy',
  'finance',
  'ngo',
  'construction',
  'hospitality',
  'banking',
  'care',
  'legal',
  'tax',
  'therapy',
  'transport',
] as const;

export type ConstellationIndustry = (typeof CONSTELLATION_INDUSTRIES)[number];

/** German is the source language of the presets and the fallback for every other locale. */
export function constellationText(text: ConstellationText | undefined, locale: Locale): string {
  if (!text) return '';
  return text[locale] ?? text.de ?? Object.values(text)[0] ?? '';
}

export function constellationIndustryLabel(
  t: TFunction<'constellations'>,
  industry: string,
): string {
  if ((CONSTELLATION_INDUSTRIES as readonly string[]).includes(industry)) {
    return t(`industry.${industry}` as 'industry.handwerk');
  }
  return industry;
}

/**
 * The preset's own label when it has one for this locale, otherwise the estimated minutes in the
 * locale's words. The number stays a machine value in the API, so a missing label never hides the
 * saving entirely.
 */
export function constellationTimeSaved(
  estimated: ConstellationTimeSaved | undefined,
  locale: Locale,
  t: TFunction<'constellations'>,
): string {
  if (!estimated) return '—';
  const label =
    estimated.label?.[locale] ?? estimated.label?.de ?? Object.values(estimated.label ?? {})[0];
  if (label) return label;
  const minutes = estimated.minutesPerWeek;
  if (typeof minutes === 'number' && minutes > 0) {
    return t('card.minutes', { minutes });
  }
  return '—';
}
