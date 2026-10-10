'use client';

import { IconButton } from '@/components/ui/button';
import { localeNames, locales, type Locale } from '@/i18n/config';
import { useLocale, useSetLocale, useTranslations } from '@/i18n/provider';
import { useAppearance } from '@/settings/appearance-provider';
import { DropdownMenu, type MenuItemDefinition } from './dropdown-menu';

/** Theme menu (light / dark / system + reset) shared by public and app chrome. */
export function ThemeMenu() {
  const t = useTranslations('nav');
  const tCommon = useTranslations('common');
  const { settings, update, reset } = useAppearance();

  const items: MenuItemDefinition[] = [
    {
      id: 'light',
      label: t('themeLight'),
      icon: 'sun',
      checked: settings.theme === 'light',
      onSelect: () => update({ theme: 'light' }),
    },
    {
      id: 'dark',
      label: t('themeDark'),
      icon: 'moon',
      checked: settings.theme === 'dark',
      onSelect: () => update({ theme: 'dark' }),
    },
    {
      id: 'system',
      label: t('themeSystem'),
      icon: 'monitor',
      checked: settings.theme === 'system',
      onSelect: () => update({ theme: 'system' }),
    },
    {
      id: 'reset',
      label: tCommon('actions.reset'),
      icon: 'refresh',
      onSelect: reset,
    },
  ];

  return (
    <DropdownMenu
      label={t('theme')}
      align="end"
      items={items}
      trigger={<IconButton label={t('theme')} icon={settings.theme === 'dark' ? 'moon' : 'sun'} />}
    />
  );
}

/** Language menu (de / en / ru) with `aria-checked` semantics via the item check. */
export function LanguageMenu() {
  const t = useTranslations('nav');
  const locale = useLocale();
  const setLocale = useSetLocale();

  const items: MenuItemDefinition[] = locales.map((item: Locale) => ({
    id: item,
    label: localeNames[item],
    icon: 'globe',
    checked: item === locale,
    onSelect: () => setLocale(item),
  }));

  return (
    <DropdownMenu
      label={t('languageChange')}
      align="end"
      items={items}
      trigger={<IconButton label={t('languageChange')} icon="globe" />}
    />
  );
}
