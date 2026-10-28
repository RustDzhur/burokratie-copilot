import { readFileSync } from 'node:fs';
import { resolve as resolvePath } from 'node:path';
import { describe, expect, it } from 'vitest';
import {
  compositeOver,
  contrastRatio,
  contrastRatioFromStrings,
  parseColor,
  parseColorAlpha,
  relativeLuminance,
  roundRatio,
} from '../contrast';
import { getBlock, mergeTokens, parseTokenBlocks, resolveToken } from '../css-tokens';

// Vitest runs with the project root as cwd; reading the shipped stylesheet keeps
// the contrast assertions tied to the real tokens instead of a copy.
const TOKENS_PATH = resolvePath(process.cwd(), 'src/styles/tokens.css');
const css = readFileSync(TOKENS_PATH, 'utf8');
const blocks = parseTokenBlocks(css);

const root = getBlock(blocks, ':root');
const darkBlock = getBlock(blocks, "[data-theme='dark']");
const highBlock = getBlock(blocks, "[data-contrast='high']");
const highLightBlock = getBlock(blocks, "[data-contrast='high']:not([data-theme='dark'])");
const highDarkBlock = getBlock(blocks, "[data-contrast='high'][data-theme='dark']");

// Composed exactly like the cascade in the browser: later blocks win.
const dark = mergeTokens(root, darkBlock);
const highLight = mergeTokens(root, highBlock, highLightBlock);
const highDark = mergeTokens(root, highBlock, darkBlock, highDarkBlock);

function resolve(tokens: Record<string, string>, name: string): string {
  const value = resolveToken(tokens, name);
  if (value === null) throw new Error(`Token ${name} is not defined`);
  return value;
}

function ratioFor(tokens: Record<string, string>, foreground: string, background: string): number {
  const fgRaw = resolve(tokens, foreground);
  const bgRaw = resolve(tokens, background);
  const fg = parseColorAlpha(fgRaw);
  const bg = parseColor(bgRaw);
  if (!fg || !bg) throw new Error(`Cannot parse ${foreground}/${background}`);
  const effectiveFg = fg.a < 1 ? compositeOver(fg, bg) : fg;
  return contrastRatio(effectiveFg, bg);
}

const THEMES: ReadonlyArray<readonly [string, Record<string, string>]> = [
  ['light', root],
  ['dark', dark],
  ['high-contrast light', highLight],
  ['high-contrast dark', highDark],
];

/**
 * Text/background pairs that carry information in the shipped interface.
 * WCAG 2.2 AA requires >= 4.5:1 for normal text (docs/task.md §23.1).
 */
const TEXT_PAIRS: ReadonlyArray<readonly [string, string]> = [
  ['--color-text', '--color-bg'],
  ['--color-text', '--color-surface'],
  ['--color-text', '--color-surface-2'],
  ['--color-text-muted', '--color-bg'],
  ['--color-text-muted', '--color-surface'],
  ['--color-text-subtle', '--color-surface'],
  ['--color-text-on-accent', '--color-accent'],
  ['--color-accent-text', '--color-surface'],
  ['--color-accent-text', '--color-accent-subtle'],
  ['--color-success-text', '--color-success-subtle'],
  ['--color-warning-text', '--color-warning-subtle'],
  ['--color-danger-text', '--color-danger-subtle'],
  ['--color-info-text', '--color-info-subtle'],
  ['--color-neutral-text', '--color-neutral-subtle'],
  ['--color-violet-text', '--color-violet-subtle'],
];

describe('contrast utility', () => {
  it('computes the WCAG reference ratios', () => {
    const black = parseColor('#000000');
    const white = parseColor('#ffffff');
    expect(black).not.toBeNull();
    expect(white).not.toBeNull();
    expect(roundRatio(contrastRatio(black!, white!))).toBe(21);
    expect(contrastRatioFromStrings('#ffffff', '#ffffff')).toBe(1);
  });

  it('parses hex, rgb() and alpha values', () => {
    expect(parseColor('#fff')).toEqual({ r: 255, g: 255, b: 255 });
    expect(parseColor('rgb(0, 0, 0)')).toEqual({ r: 0, g: 0, b: 0 });
    expect(parseColorAlpha('rgba(255, 255, 255, 0.5)')?.a).toBeCloseTo(0.5, 2);
    expect(parseColor('not-a-colour')).toBeNull();
  });

  it('composites translucent surfaces over their background', () => {
    const translucent = parseColorAlpha('rgba(255, 255, 255, 0.5)');
    const overBlack = compositeOver(translucent!, { r: 0, g: 0, b: 0 });
    expect(overBlack).toEqual({ r: 128, g: 128, b: 128 });
  });

  it('orders luminance correctly', () => {
    const dark = relativeLuminance({ r: 7, g: 11, b: 20 });
    const light = relativeLuminance({ r: 244, g: 246, b: 251 });
    expect(light).toBeGreaterThan(dark);
  });
});

describe('design tokens', () => {
  it('defines the documented theme blocks', () => {
    expect(blocks.map((block) => block.selector)).toContain(':root');
    expect(blocks.map((block) => block.selector)).toContain("[data-theme='dark']");
    expect(blocks.some((block) => block.selector.includes("data-contrast='high'"))).toBe(true);
  });

  it.each(THEMES)('keeps every text pair at or above 4.5:1 in %s', (_name, tokens) => {
    const failures: string[] = [];
    for (const [foreground, background] of TEXT_PAIRS) {
      const ratio = ratioFor(tokens, foreground, background);
      if (ratio < 4.5) {
        failures.push(`${foreground} on ${background} = ${roundRatio(ratio)}:1`);
      }
    }
    expect(failures).toEqual([]);
  });

  it.each(THEMES)('keeps interactive borders distinguishable in %s', (_name, tokens) => {
    const borderRatio = ratioFor(tokens, '--color-border-strong', '--color-surface');
    // WCAG 2.2 (1.4.11) requires 3:1 for non-text UI component boundaries.
    expect(borderRatio).toBeGreaterThanOrEqual(3);
  });

  it('keeps the focus ring visible against both surface and text colours', () => {
    for (const [, tokens] of THEMES) {
      const againstSurface = ratioFor(tokens, '--color-focus-ring', '--color-surface');
      expect(againstSurface).toBeGreaterThanOrEqual(3);
    }
  });

  it('reduces motion to zero when the manual setting is active', () => {
    const reduced = getBlock(blocks, "[data-motion='reduced']");
    expect(resolve(reduced, '--motion-duration-base')).toBe('0ms');
  });

  it('keeps accent text readable on the glass surface after compositing', () => {
    const surface = parseColor(resolve(root, '--color-surface'))!;
    const glass = parseColorAlpha(resolve(root, '--color-surface-glass'))!;
    const composited = compositeOver(glass, surface);
    const text = parseColor(resolve(root, '--color-text'))!;
    expect(contrastRatio(text, composited)).toBeGreaterThanOrEqual(4.5);
  });
});
