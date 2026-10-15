import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import { describe, expect, it } from 'vitest';
import { catalogues } from '../messages';

const SRC = join(__dirname, '..', '..');

function sources(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) return name === '__tests__' ? [] : sources(path);
    return /\.(ts|tsx)$/.test(name) && !/\.test\./.test(name) ? [path] : [];
  });
}

/** The files that may format a percentage themselves: the formatter, the provider that exposes it, and the hook that labels it. */
const ALLOWED = new Set(['i18n/format.ts', 'i18n/provider.tsx', 'i18n/use-estimate.ts']);

describe('UI-AI-01: a confidence of a model is never shown as a bare percentage', () => {
  it('no screen formats a percentage by itself; it goes through useEstimateText, which adds the label', () => {
    const offenders = sources(SRC)
      .map((file) => relative(SRC, file))
      .filter((file) => !ALLOWED.has(file))
      .filter((file) => /\bformatPercent\(|formatters\.percent\(|\.percent\(/.test(readFileSync(join(SRC, file), 'utf8')));
    expect(offenders).toEqual([]);
  });

  it("every message that states a confidence says in every language that it is the model's estimate", () => {
    const wanted = { de: /Modellschätzung/, en: /model estimate/, ru: /оценка модели/ } as const;
    for (const lang of ['de', 'en', 'ru'] as const) {
      const dictionary = catalogues[lang] as unknown as Record<string, Record<string, string>>;
      expect(dictionary.radar?.['detail.confidence'], `${lang} radar`).toMatch(wanted[lang]);
      expect(dictionary.documentViewer?.['flow.reason.LOW_CONFIDENCE'], `${lang} flow`).toMatch(wanted[lang]);
      expect(dictionary.common?.['estimate.label'], `${lang} label`).toMatch(wanted[lang]);
    }
  });
});
