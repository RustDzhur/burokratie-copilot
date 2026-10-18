'use client';

import Link from 'next/link';
import { Icon, type IconName } from '@/components/icons';
import { PageHeader } from '@/components/app/resource-view';
import { Card, CardBody, CardHeader } from '@/components/ui';
import { useTranslations } from '@/i18n/provider';
import { useSession } from '@/lib/auth/session-context';

interface Entry {
  readonly key:
    | 'profile'
    | 'security'
    | 'accessibility'
    | 'notifications'
    | 'billing'
    | 'rules'
    | 'dateMarkers';
  readonly href: string;
  readonly icon: IconName;
  /** A backend permission needed to use the screen; an unknown permission list hides nothing. */
  readonly permission?: string;
}

const ENTRIES: readonly Entry[] = [
  { key: 'profile', href: '/profile', icon: 'user' },
  { key: 'security', href: '/security', icon: 'shield' },
  { key: 'accessibility', href: '/accessibility', icon: 'accessibility' },
  { key: 'notifications', href: '/notifications', icon: 'bell' },
  { key: 'billing', href: '/billing', icon: 'credit-card' },
  { key: 'rules', href: '/rules', icon: 'gear' },
  {
    key: 'dateMarkers',
    href: '/settings/date-markers',
    icon: 'calendar',
    permission: 'settings.view',
  },
];

/** The hub of the account settings: one place that leads to every setting a person can change. */
export default function AccountSettingsPage() {
  const t = useTranslations('accountSettings');
  const session = useSession();
  const permissions = session?.permissions;
  const entries =
    permissions === undefined
      ? ENTRIES
      : ENTRIES.filter(
          (entry) => entry.permission === undefined || permissions.includes(entry.permission),
        );

  return (
    <>
      <PageHeader title={t('title')} subtitle={t('subtitle')} />
      <ul
        style={{
          listStyle: 'none',
          padding: 0,
          display: 'grid',
          gap: '1rem',
          gridTemplateColumns: 'repeat(auto-fill, minmax(260px, 1fr))',
        }}
      >
        {entries.map((entry) => (
          <li key={entry.key}>
            <Card interactive>
              <CardHeader
                title={<Link href={entry.href}>{t(`${entry.key}.title` as 'profile.title')}</Link>}
                description={t(`${entry.key}.description` as 'profile.description')}
                icon={entry.icon}
                headingLevel={2}
              />
              <CardBody>
                <Link href={entry.href}>
                  {t('open')} <Icon name="arrow-right" size={15} />
                </Link>
              </CardBody>
            </Card>
          </li>
        ))}
      </ul>
    </>
  );
}
