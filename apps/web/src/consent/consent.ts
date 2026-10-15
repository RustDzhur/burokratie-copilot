/**
 * What the visitor allowed to be stored in the browser.
 *
 * Two kinds of storage exist here. The necessary ones (the sign-in cookie, the protection against forged requests, and this very
 * choice) need no consent. The convenience ones (the language, the look of the page, the help assistant's conversation) are kept only
 * if the visitor said yes; without a yes the page still works and simply starts from the defaults on the next visit. Nothing is
 * stored for tracking or advertising, because there is none.
 */
export const CONSENT_COOKIE = 'bc_consent';
/** The choice is asked again after this long, as the guidance of the data protection authorities recommends. */
const MAX_AGE_SECONDS = 60 * 60 * 24 * 180;

export interface Consent {
  /** Whether the visitor has answered at all. */
  readonly decided: boolean;
  /** Whether the convenience storage (language, appearance, assistant conversation) is allowed. */
  readonly comfort: boolean;
}

export const CONSENT_CHANGED = 'bc-consent-changed';
export const CONSENT_OPEN = 'bc-consent-open';

const COMFORT_COOKIES = ['bc_locale', 'bc_appearance'];
const COMFORT_STORAGE = ['bc.appearance', 'bc.locale'];

export function readConsent(): Consent {
  if (typeof document === 'undefined') return { decided: false, comfort: false };
  const match = document.cookie.match(new RegExp(`(?:^|; )${CONSENT_COOKIE}=([^;]*)`));
  const value = match?.[1] ?? '';
  if (value === 'v1.comfort') return { decided: true, comfort: true };
  if (value === 'v1.necessary') return { decided: true, comfort: false };
  return { decided: false, comfort: false };
}

/** Whether a convenience cookie or storage entry may be written right now. */
export function comfortAllowed(): boolean {
  return readConsent().comfort;
}

/** Records the choice; refusing also removes what was stored before. */
export function setConsent(comfort: boolean): void {
  if (typeof document === 'undefined') return;
  document.cookie = `${CONSENT_COOKIE}=${comfort ? 'v1.comfort' : 'v1.necessary'}; path=/; max-age=${MAX_AGE_SECONDS}; samesite=lax`;
  if (!comfort) {
    for (const name of COMFORT_COOKIES) {
      document.cookie = `${name}=; path=/; max-age=0; samesite=lax`;
    }
    try {
      for (const key of COMFORT_STORAGE) window.localStorage.removeItem(key);
      window.sessionStorage.removeItem('bc.iris.conversation');
    } catch {
      // Storage may be unavailable; there is then nothing to remove.
    }
  }
  window.dispatchEvent(new Event(CONSENT_CHANGED));
}

/** Opens the choice again (the "Cookie settings" link). */
export function openConsent(): void {
  if (typeof window !== 'undefined') window.dispatchEvent(new Event(CONSENT_OPEN));
}
