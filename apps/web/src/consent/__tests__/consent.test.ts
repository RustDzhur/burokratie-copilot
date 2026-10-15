import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { comfortAllowed, readConsent, setConsent } from '../consent';
import { applyAppearance } from '@/settings/appearance-provider';
import { defaultAppearance } from '@/settings/appearance';

function clear(): void {
  for (const part of document.cookie.split(';')) {
    const name = part.split('=')[0]?.trim();
    if (name) document.cookie = `${name}=; path=/; max-age=0`;
  }
  localStorage.clear();
  sessionStorage.clear();
}

describe('cookie consent', () => {
  beforeEach(clear);
  afterEach(clear);

  it('starts undecided with nothing allowed, and the convenience storage stays empty until the visitor says yes', () => {
    expect(readConsent()).toEqual({ decided: false, comfort: false });
    expect(comfortAllowed()).toBe(false);

    applyAppearance({ ...defaultAppearance, theme: 'dark' });
    expect(document.cookie).not.toContain('bc_appearance');
    expect(localStorage.getItem('bc.appearance')).toBeNull();
    // The look is still applied to the page, it is only not kept.
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
  });

  it('keeps the look for the next visit after "accept all"', () => {
    setConsent(true);
    expect(readConsent()).toEqual({ decided: true, comfort: true });
    applyAppearance({ ...defaultAppearance, theme: 'dark' });
    expect(document.cookie).toContain('bc_appearance');
    expect(localStorage.getItem('bc.appearance')).not.toBeNull();
  });

  it('refusing removes what was kept before, and keeps only the choice itself', () => {
    setConsent(true);
    applyAppearance({ ...defaultAppearance, theme: 'dark' });
    document.cookie = 'bc_locale=ru; path=/';
    sessionStorage.setItem('bc.iris.conversation', '[]');

    setConsent(false);

    expect(readConsent()).toEqual({ decided: true, comfort: false });
    expect(document.cookie).not.toContain('bc_appearance');
    expect(document.cookie).not.toContain('bc_locale');
    expect(localStorage.getItem('bc.appearance')).toBeNull();
    expect(sessionStorage.getItem('bc.iris.conversation')).toBeNull();
  });
});
