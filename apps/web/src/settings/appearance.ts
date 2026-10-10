/**
 * Appearance and accessibility preferences (docs/task.md §23).
 *
 * All settings are applied as data attributes on <html> so that the CSS token
 * layer can react without JavaScript in the component tree:
 *
 *   data-theme      light | dark
 *   data-contrast   normal | high
 *   data-text-scale 100 | 112 | 125
 *   data-motion     auto | reduced
 *   data-effects    full | reduced
 *   data-color-cues off | patterns
 *   data-density    comfortable | compact
 *
 * They are persisted in localStorage (per browser) and mirrored into a cookie so
 * the first server render can already emit the correct attributes.
 */

export const APPEARANCE_STORAGE_KEY = 'bc.appearance';
export const APPEARANCE_COOKIE = 'bc_appearance';

export type ThemePreference = 'light' | 'dark' | 'system';
export type ContrastPreference = 'normal' | 'high';
export type TextScale = 100 | 112 | 125;
export type MotionPreference = 'auto' | 'reduced';
export type EffectsPreference = 'full' | 'reduced';
export type ColorCuePreference = 'off' | 'patterns';
export type DensityPreference = 'comfortable' | 'compact';

export interface AppearanceSettings {
  readonly theme: ThemePreference;
  readonly contrast: ContrastPreference;
  readonly textScale: TextScale;
  readonly motion: MotionPreference;
  readonly effects: EffectsPreference;
  readonly colorCues: ColorCuePreference;
  readonly density: DensityPreference;
}

export const defaultAppearance: AppearanceSettings = {
  theme: 'system',
  contrast: 'normal',
  textScale: 100,
  motion: 'auto',
  effects: 'full',
  colorCues: 'off',
  density: 'comfortable',
};

const themeValues: readonly ThemePreference[] = ['light', 'dark', 'system'];
const contrastValues: readonly ContrastPreference[] = ['normal', 'high'];
const textScaleValues: readonly TextScale[] = [100, 112, 125];
const motionValues: readonly MotionPreference[] = ['auto', 'reduced'];
const effectsValues: readonly EffectsPreference[] = ['full', 'reduced'];
const colorCueValues: readonly ColorCuePreference[] = ['off', 'patterns'];
const densityValues: readonly DensityPreference[] = ['comfortable', 'compact'];

function oneOf<T extends string | number>(value: unknown, allowed: readonly T[], fallback: T): T {
  return allowed.includes(value as T) ? (value as T) : fallback;
}

/** Parses untrusted input (cookie / localStorage / API) into valid settings. */
export function parseAppearance(input: unknown): AppearanceSettings {
  if (typeof input !== 'object' || input === null) return defaultAppearance;
  const record = input as Record<string, unknown>;
  return {
    theme: oneOf(record.theme, themeValues, defaultAppearance.theme),
    contrast: oneOf(record.contrast, contrastValues, defaultAppearance.contrast),
    textScale: oneOf(Number(record.textScale), textScaleValues, defaultAppearance.textScale),
    motion: oneOf(record.motion, motionValues, defaultAppearance.motion),
    effects: oneOf(record.effects, effectsValues, defaultAppearance.effects),
    colorCues: oneOf(record.colorCues, colorCueValues, defaultAppearance.colorCues),
    density: oneOf(record.density, densityValues, defaultAppearance.density),
  };
}

export function parseAppearanceCookie(raw: string | undefined): AppearanceSettings {
  if (!raw) return defaultAppearance;
  try {
    return parseAppearance(JSON.parse(decodeURIComponent(raw)));
  } catch {
    return defaultAppearance;
  }
}

export function serializeAppearance(settings: AppearanceSettings): string {
  return encodeURIComponent(JSON.stringify(settings));
}

/** Resolves `system` against the OS preference; safe without `window`. */
export function resolveTheme(preference: ThemePreference, prefersDark: boolean): 'light' | 'dark' {
  if (preference === 'system') return prefersDark ? 'dark' : 'light';
  return preference;
}

export interface AppearanceAttributes {
  readonly 'data-theme': 'light' | 'dark';
  readonly 'data-contrast': ContrastPreference;
  readonly 'data-text-scale': string;
  readonly 'data-motion': MotionPreference;
  readonly 'data-effects': EffectsPreference;
  readonly 'data-color-cues': ColorCuePreference;
  readonly 'data-density': DensityPreference;
}

export function appearanceAttributes(
  settings: AppearanceSettings,
  prefersDark: boolean,
  prefersReducedMotion: boolean,
): AppearanceAttributes {
  return {
    'data-theme': resolveTheme(settings.theme, prefersDark),
    'data-contrast': settings.contrast,
    'data-text-scale': String(settings.textScale),
    'data-motion': settings.motion === 'reduced' || prefersReducedMotion ? 'reduced' : 'auto',
    'data-effects': settings.effects,
    'data-color-cues': settings.colorCues,
    'data-density': settings.density,
  };
}

/** Blocking script that applies stored settings before the first paint. */
export function appearanceBootstrapScript(): string {
  return `(function(){try{
var s=JSON.parse(decodeURIComponent((document.cookie.match(/(?:^|; )${APPEARANCE_COOKIE}=([^;]*)/)||[])[1]||'null')||'null')
 ||JSON.parse(localStorage.getItem('${APPEARANCE_STORAGE_KEY}')||'null')||{};
var m=window.matchMedia;
var dark=s.theme==='dark'||((s.theme===undefined||s.theme==='system')&&m&&m('(prefers-color-scheme: dark)').matches);
var rm=s.motion==='reduced'||(m&&m('(prefers-reduced-motion: reduce)').matches);
var r=document.documentElement;
r.setAttribute('data-theme',dark?'dark':'light');
r.setAttribute('data-contrast',s.contrast==='high'?'high':'normal');
r.setAttribute('data-text-scale',String(s.textScale===112||s.textScale===125?s.textScale:100));
r.setAttribute('data-motion',rm?'reduced':'auto');
r.setAttribute('data-effects',s.effects==='reduced'?'reduced':'full');
r.setAttribute('data-color-cues',s.colorCues==='patterns'?'patterns':'off');
r.setAttribute('data-density',s.density==='compact'?'compact':'comfortable');
}catch(e){}})();`;
}
