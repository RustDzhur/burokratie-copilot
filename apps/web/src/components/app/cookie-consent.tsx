'use client';

import Link from 'next/link';
import { useEffect, useState } from 'react';
import { Button } from '@/components/ui/button';
import { Checkbox } from '@/components/ui/form';
import { useTranslations } from '@/i18n/provider';
import { CONSENT_OPEN, openConsent, readConsent, setConsent } from '@/consent/consent';

/**
 * The cookie notice. It does not block the page. "Only necessary" and "Accept all" are two buttons of the same weight, and a third
 * opens the detail with one switch for the convenience storage. The choice is remembered and can be changed at any time from the
 * link in the footer.
 */
export function CookieConsent() {
  const t = useTranslations('consent');
  const [open, setOpen] = useState(false);
  const [details, setDetails] = useState(false);
  const [comfort, setComfort] = useState(false);

  useEffect(() => {
    const current = readConsent();
    setComfort(current.comfort);
    setOpen(!current.decided);
    const reopen = (): void => {
      setComfort(readConsent().comfort);
      setDetails(true);
      setOpen(true);
    };
    window.addEventListener(CONSENT_OPEN, reopen);
    return () => window.removeEventListener(CONSENT_OPEN, reopen);
  }, []);

  if (!open) return null;

  const decide = (allowComfort: boolean): void => {
    setConsent(allowComfort);
    setOpen(false);
    setDetails(false);
  };

  return (
    // A section already has the implicit role of region; naming it again is redundant, and eslint's
    // jsx-a11y rule is right to say so. The aria-label is what makes the region identifiable.
    <section
      aria-label={t('label')}
      style={{
        position: 'fixed', left: 12, right: 12, bottom: 12, zIndex: 10001, maxWidth: 720, margin: '0 auto', padding: '1rem',
        background: 'var(--color-surface, #fff)', color: 'var(--color-text, #111)', border: '1px solid var(--color-border, #ccc)',
        borderRadius: 12, boxShadow: '0 12px 40px rgba(0,0,0,0.35)',
      }}
    >
      <h2 style={{ margin: '0 0 0.5rem', fontSize: '1.05rem' }}>{t('title')}</h2>
      <p style={{ margin: '0 0 0.75rem' }}>
        {t('text')} <Link href="/cookies">{t('more')}</Link> · <Link href="/datenschutz">{t('privacy')}</Link>
      </p>
      {details ? (
        <div style={{ display: 'grid', gap: '0.5rem', margin: '0 0 0.75rem' }}>
          <Checkbox label={t('necessary.label')} description={t('necessary.description')} checked disabled readOnly />
          <Checkbox
            label={t('comfort.label')}
            description={t('comfort.description')}
            checked={comfort}
            onChange={(event) => setComfort(event.target.checked)}
          />
        </div>
      ) : null}
      <div style={{ display: 'flex', gap: '0.5rem', flexWrap: 'wrap' }}>
        <Button variant="secondary" onClick={() => decide(false)}>
          {t('onlyNecessary')}
        </Button>
        <Button variant="secondary" onClick={() => decide(true)}>
          {t('acceptAll')}
        </Button>
        {details ? (
          <Button variant="primary" onClick={() => decide(comfort)}>
            {t('save')}
          </Button>
        ) : (
          <Button variant="ghost" onClick={() => setDetails(true)}>
            {t('settings')}
          </Button>
        )}
      </div>
    </section>
  );
}

/** The footer link that opens the choice again. */
export function CookieSettingsButton() {
  const t = useTranslations('consent');
  return (
    <button
      type="button"
      onClick={openConsent}
      style={{ background: 'none', border: 0, padding: 0, font: 'inherit', color: 'inherit', textDecoration: 'underline', cursor: 'pointer' }}
    >
      {t('footerLink')}
    </button>
  );
}
