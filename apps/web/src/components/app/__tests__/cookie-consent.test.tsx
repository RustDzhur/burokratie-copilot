import { afterEach, describe, expect, it } from 'vitest';
import { renderWithProviders, screen, userEvent, waitFor } from '@/test/render';
import { readConsent } from '@/consent/consent';
import { CookieConsent, CookieSettingsButton } from '../cookie-consent';

function clear(): void {
  for (const part of document.cookie.split(';')) {
    const name = part.split('=')[0]?.trim();
    if (name) document.cookie = `${name}=; path=/; max-age=0`;
  }
}

describe('CookieConsent', () => {
  afterEach(clear);

  it('asks once, offers "only necessary" and "accept all" with the same weight, and remembers the answer', async () => {
    const user = userEvent.setup();
    clear();
    renderWithProviders(<CookieConsent />);

    expect(await screen.findByRole('region', { name: 'Cookie-Hinweis' })).toBeInTheDocument();
    const necessary = screen.getByRole('button', { name: 'Nur notwendige' });
    const all = screen.getByRole('button', { name: 'Alle akzeptieren' });
    expect(necessary.className).toBe(all.className);

    await user.click(necessary);
    expect(readConsent()).toEqual({ decided: true, comfort: false });
    expect(screen.queryByRole('region', { name: 'Cookie-Hinweis' })).not.toBeInTheDocument();
  });

  it('does not ask again once answered, and the footer link opens the choice again with the details', async () => {
    const user = userEvent.setup();
    document.cookie = 'bc_consent=v1.necessary; path=/';
    renderWithProviders(
      <>
        <CookieConsent />
        <CookieSettingsButton />
      </>,
    );
    expect(screen.queryByRole('region', { name: 'Cookie-Hinweis' })).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Cookie-Einstellungen' }));
    expect(await screen.findByRole('region', { name: 'Cookie-Hinweis' })).toBeInTheDocument();
    await user.click(screen.getByRole('checkbox', { name: /Komfort/ }));
    await user.click(screen.getByRole('button', { name: 'Auswahl speichern' }));
    await waitFor(() => expect(readConsent()).toEqual({ decided: true, comfort: true }));
  });

  it('the necessary entry cannot be switched off', async () => {
    const user = userEvent.setup();
    clear();
    renderWithProviders(<CookieConsent />);
    await user.click(await screen.findByRole('button', { name: 'Einstellungen' }));
    expect(screen.getByRole('checkbox', { name: /Notwendig/ })).toBeDisabled();
    expect(screen.getByRole('checkbox', { name: /Notwendig/ })).toBeChecked();
  });
});
