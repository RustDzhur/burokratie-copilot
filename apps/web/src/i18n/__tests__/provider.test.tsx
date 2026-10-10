import { describe, expect, it } from 'vitest';
import { renderWithProviders, screen, userEvent } from '@/test/render';
import { useFormatters, useLocale, useSetLocale, useTranslations } from '../provider';
import { locales } from '../config';
import { catalogues, namespaces } from '../messages';

function Probe() {
  const t = useTranslations('common');
  const tNav = useTranslations('nav');
  const tStates = useTranslations('states');
  const locale = useLocale();
  const setLocale = useSetLocale();
  const formatters = useFormatters();

  return (
    <div>
      <p data-testid="locale">{locale}</p>
      <p data-testid="action">{t('actions.save')}</p>
      <p data-testid="nav">{tNav('items.dashboard')}</p>
      <p data-testid="plural">{tStates('empty.title')}</p>
      <p data-testid="date">{formatters.date('2026-01-15T12:00:00Z')}</p>
      <p data-testid="currency">{formatters.currency(1234.56)}</p>
      <p data-testid="count">{t('labels.results', { count: 3 })}</p>
      <button type="button" onClick={() => setLocale('en')}>
        EN
      </button>
      <button type="button" onClick={() => setLocale('ru')}>
        RU
      </button>
      <button type="button" onClick={() => setLocale('de')}>
        DE
      </button>
    </div>
  );
}

describe('i18n provider', () => {
  it('renders German by default with German formatting', () => {
    renderWithProviders(<Probe />);

    expect(screen.getByTestId('locale')).toHaveTextContent('de');
    expect(screen.getByTestId('action')).toHaveTextContent('Speichern');
    expect(screen.getByTestId('nav')).toHaveTextContent('Dashboard');
    expect(screen.getByTestId('date')).toHaveTextContent('15.01.2026');
    expect(screen.getByTestId('currency').textContent).toContain('1.234,56');
    expect(screen.getByTestId('count')).toHaveTextContent('3 Einträge');
  });

  it('switches the whole interface to English and Russian at runtime', async () => {
    renderWithProviders(<Probe />);

    await userEvent.click(screen.getByRole('button', { name: 'EN' }));
    expect(screen.getByTestId('locale')).toHaveTextContent('en');
    expect(screen.getByTestId('action')).toHaveTextContent('Save');
    expect(screen.getByTestId('nav')).toHaveTextContent('Dashboard');
    expect(screen.getByTestId('date')).toHaveTextContent('15/01/2026');
    expect(document.documentElement.lang).toBe('en');

    await userEvent.click(screen.getByRole('button', { name: 'RU' }));
    expect(screen.getByTestId('locale')).toHaveTextContent('ru');
    expect(screen.getByTestId('action')).toHaveTextContent('Сохранить');
    expect(screen.getByTestId('nav')).toHaveTextContent('Дашборд');
    expect(document.documentElement.lang).toBe('ru');

    await userEvent.click(screen.getByRole('button', { name: 'DE' }));
    expect(screen.getByTestId('action')).toHaveTextContent('Speichern');
  });

  it('persists the locale choice in a cookie for the next server render', async () => {
    renderWithProviders(<Probe />);
    await userEvent.click(screen.getByRole('button', { name: 'EN' }));
    expect(document.cookie).toContain('bc_locale=en');
  });

  it('returns an empty string for unknown keys instead of leaking a placeholder', () => {
    function UnknownKeyProbe() {
      const t = useTranslations('common');
      const translate = t as unknown as (key: string) => string;
      return <p data-testid="missing">{translate('does.not.exist')}</p>;
    }
    renderWithProviders(<UnknownKeyProbe />);
    expect(screen.getByTestId('missing')).toHaveTextContent('');
  });
});

describe('catalogues', () => {
  it('ships all three locales', () => {
    expect(locales).toEqual(['de', 'en', 'ru']);
    for (const locale of locales) {
      expect(Object.keys(catalogues[locale]).length).toBeGreaterThan(20);
    }
  });

  it('keeps every namespace and key in sync across locales', () => {
    const german = catalogues.de;
    for (const namespace of namespaces) {
      const deKeys = Object.keys(german[namespace]).sort();
      for (const locale of ['en', 'ru'] as const) {
        const otherKeys = Object.keys(catalogues[locale][namespace]).sort();
        expect(otherKeys, `${locale}.${String(namespace)}`).toEqual(deKeys);
      }
    }
  });

  it('contains no empty strings', () => {
    for (const locale of locales) {
      for (const namespace of namespaces) {
        for (const [key, value] of Object.entries(catalogues[locale][namespace])) {
          expect(value, `${locale}.${String(namespace)}.${key}`).not.toBe('');
        }
      }
    }
  });
});
