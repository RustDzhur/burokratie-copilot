/**
 * Colour maths for the design system.
 *
 * Used by the automated contrast test (WCAG 2.2 AA requires >= 4.5:1 for text)
 * and available to product code that needs to reason about colour, e.g. the
 * accessibility settings preview. Kept dependency-free and fully typed.
 */

export interface Rgb {
  readonly r: number;
  readonly g: number;
  readonly b: number;
}

export interface Rgba extends Rgb {
  readonly a: number;
}

const HEX_PATTERN = /^#([0-9a-f]{3}|[0-9a-f]{4}|[0-9a-f]{6}|[0-9a-f]{8})$/i;
const RGB_PATTERN =
  /^rgba?\(\s*([0-9.]+)[\s,]+([0-9.]+)[\s,]+([0-9.]+)(?:[\s,/]+([0-9.%]+))?\s*\)/i;

/** Parses `#rgb`, `#rgba`, `#rrggbb`, `#rrggbbaa` and `rgb()/rgba()` including alpha. */
export function parseColorAlpha(input: string): Rgba | null {
  const value = input.trim();
  const hex = HEX_PATTERN.exec(value);
  if (hex?.[1]) {
    const raw = hex[1];
    if (raw.length === 3 || raw.length === 4) {
      const [r, g, b, a] = raw.split('');
      if (!r || !g || !b) return null;
      return {
        r: Number.parseInt(r + r, 16),
        g: Number.parseInt(g + g, 16),
        b: Number.parseInt(b + b, 16),
        a: a ? Number.parseInt(a + a, 16) / 255 : 1,
      };
    }
    return {
      r: Number.parseInt(raw.slice(0, 2), 16),
      g: Number.parseInt(raw.slice(2, 4), 16),
      b: Number.parseInt(raw.slice(4, 6), 16),
      a: raw.length === 8 ? Number.parseInt(raw.slice(6, 8), 16) / 255 : 1,
    };
  }

  const rgb = RGB_PATTERN.exec(value);
  if (rgb?.[1] && rgb[2] && rgb[3]) {
    return {
      r: clampChannel(Number.parseFloat(rgb[1])),
      g: clampChannel(Number.parseFloat(rgb[2])),
      b: clampChannel(Number.parseFloat(rgb[3])),
      a: rgb[4] ? parseAlpha(rgb[4]) : 1,
    };
  }

  return null;
}

function parseAlpha(raw: string): number {
  if (raw.endsWith('%')) {
    const percent = Number.parseFloat(raw.slice(0, -1));
    if (Number.isNaN(percent)) return 1;
    return Math.min(1, Math.max(0, percent / 100));
  }
  const value = Number.parseFloat(raw);
  if (Number.isNaN(value)) return 1;
  return Math.min(1, Math.max(0, value));
}

/** Parses a CSS colour into 0-255 channels, ignoring any alpha channel. */
export function parseColor(input: string): Rgb | null {
  const parsed = parseColorAlpha(input);
  if (!parsed) return null;
  return { r: parsed.r, g: parsed.g, b: parsed.b };
}

/** Alpha-composites `top` over an opaque `bottom`, as a browser would render it. */
export function compositeOver(top: Rgba, bottom: Rgb): Rgb {
  if (top.a >= 1) return { r: top.r, g: top.g, b: top.b };
  const mix = (t: number, b: number): number => Math.round(t * top.a + b * (1 - top.a));
  return { r: mix(top.r, bottom.r), g: mix(top.g, bottom.g), b: mix(top.b, bottom.b) };
}

function clampChannel(value: number): number {
  if (Number.isNaN(value)) return 0;
  return Math.min(255, Math.max(0, Math.round(value)));
}

function channelToLinear(channel: number): number {
  const normalized = channel / 255;
  return normalized <= 0.03928 ? normalized / 12.92 : ((normalized + 0.055) / 1.055) ** 2.4;
}

/** WCAG 2.x relative luminance. */
export function relativeLuminance(color: Rgb): number {
  return (
    0.2126 * channelToLinear(color.r) +
    0.7152 * channelToLinear(color.g) +
    0.0722 * channelToLinear(color.b)
  );
}

/** WCAG 2.x contrast ratio between two colours (order independent, 1..21). */
export function contrastRatio(a: Rgb, b: Rgb): number {
  const la = relativeLuminance(a);
  const lb = relativeLuminance(b);
  const lighter = Math.max(la, lb);
  const darker = Math.min(la, lb);
  return (lighter + 0.05) / (darker + 0.05);
}

/** Contrast ratio helper that accepts two CSS colour strings. */
export function contrastRatioFromStrings(foreground: string, background: string): number | null {
  const fg = parseColor(foreground);
  const bg = parseColor(background);
  if (!fg || !bg) return null;
  return contrastRatio(fg, bg);
}

/** Rounds to two decimals for readable assertions and reports. */
export function roundRatio(ratio: number): number {
  return Math.round(ratio * 100) / 100;
}
