'use client';

import { useRef, useState } from 'react';
import { PageHeader, PageSection, ResourceView } from '@/components/app/resource-view';
import { Avatar, Card, CardBody } from '@/components/ui';
import { Button } from '@/components/ui/button';
import { Input, Select } from '@/components/ui/form';
import { InlineAlert } from '@/components/ui/feedback';
import { useToast } from '@/components/ui/toast';
import { useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { toApiError } from '@/lib/api/errors';
import { useApiResource } from '@/lib/api/hooks';
import type { ProfileData } from '@/lib/api/types';
import { squareAvatar } from '@/lib/image/avatar';

const LOCALES = [
  { value: 'de', label: 'Deutsch' },
  { value: 'en', label: 'English' },
  { value: 'ru', label: 'Русский' },
] as const;

function zones(current: string): readonly string[] {
  const supported =
    typeof Intl !== 'undefined' && 'supportedValuesOf' in Intl
      ? (Intl as unknown as { supportedValuesOf: (key: string) => string[] }).supportedValuesOf('timeZone')
      : [];
  const base = supported.length > 0 ? supported : ['Europe/Berlin', 'Europe/Vienna', 'Europe/Zurich', 'Europe/Kyiv', 'Europe/Moscow', 'UTC'];
  return base.includes(current) ? base : [current, ...base];
}

/** The person's own profile: photo, name, language and time zone. The e-mail address is shown, not changed here. */
export default function ProfilePage() {
  const t = useTranslations('profile');
  const { state, reload, isRefreshing } = useApiResource((signal) => api.profile.get(signal), []);

  return (
    <>
      <PageHeader title={t('title')} subtitle={t('subtitle')} />
      <ResourceView state={state} onRetry={reload} isRefreshing={isRefreshing} emptyState={null}>
        {(profile) => <ProfileForm profile={profile} />}
      </ResourceView>
    </>
  );
}

function ProfileForm({ profile }: { readonly profile: ProfileData }) {
  const t = useTranslations('profile');
  const toast = useToast();
  const fileInput = useRef<HTMLInputElement | null>(null);
  const [name, setName] = useState(profile.displayName);
  const [locale, setLocale] = useState<string>(profile.locale);
  const [timeZone, setTimeZone] = useState(profile.timeZone);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const avatarUrl = profile.avatarVersion ? `/api/v1/profile/avatar?v=${profile.avatarVersion}` : null;

  const save = async (): Promise<void> => {
    setProblem(null);
    if (name.trim().length < 2) {
      setProblem(t('nameRequired'));
      return;
    }
    setBusy(true);
    try {
      await api.profile.update({ displayName: name.trim(), locale, timeZone });
      toast.success(t('saved'));
      // The language and the name are read when the page loads: load it anew so the whole screen follows.
      window.location.reload();
    } catch (error) {
      setProblem(toApiError(error).message || t('photo.failed'));
    } finally {
      setBusy(false);
    }
  };

  const choose = async (file: File | undefined): Promise<void> => {
    if (!file) return;
    setProblem(null);
    setBusy(true);
    try {
      await api.profile.uploadAvatar(await squareAvatar(file));
      toast.success(t('photo.saved'));
      window.location.reload();
    } catch (error) {
      setProblem(toApiError(error).message || t('photo.failed'));
    } finally {
      setBusy(false);
    }
  };

  const remove = async (): Promise<void> => {
    setBusy(true);
    try {
      await api.profile.removeAvatar();
      toast.success(t('photo.removed'));
      window.location.reload();
    } catch (error) {
      toast.error(toApiError(error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <PageSection title={t('photo.title')}>
        <Card>
          <CardBody>
            <div style={{ display: 'flex', alignItems: 'center', gap: '1rem', flexWrap: 'wrap' }}>
              <Avatar name={profile.displayName} src={avatarUrl} size="lg" />
              <div style={{ display: 'flex', gap: '0.5rem', flexWrap: 'wrap' }}>
                <input
                  ref={fileInput}
                  type="file"
                  accept="image/png,image/jpeg,image/webp"
                  hidden
                  aria-label={t('photo.choose')}
                  onChange={(event) => void choose(event.target.files?.[0])}
                />
                <Button loading={busy} onClick={() => fileInput.current?.click()}>
                  {avatarUrl ? t('photo.change') : t('photo.choose')}
                </Button>
                {avatarUrl ? (
                  <Button variant="ghost" disabled={busy} onClick={() => void remove()}>
                    {t('photo.remove')}
                  </Button>
                ) : null}
              </div>
            </div>
            <p>{t('photo.hint')}</p>
          </CardBody>
        </Card>
      </PageSection>
      <PageSection title={t('data.title')}>
        <Card>
          <CardBody>
            <Input label={t('data.email')} value={profile.email} readOnly hint={t('data.emailHint')} />
            <Input label={t('data.name')} value={name} onChange={(event) => setName(event.target.value)} />
            <Select
              label={t('data.language')}
              value={locale}
              onChange={(event) => setLocale(event.target.value)}
              options={LOCALES.map((item) => ({ value: item.value, label: item.label }))}
            />
            <Select
              label={t('data.timeZone')}
              value={timeZone}
              onChange={(event) => setTimeZone(event.target.value)}
              options={zones(profile.timeZone).map((zone) => ({ value: zone, label: zone }))}
            />
            {problem ? <InlineAlert tone="danger">{problem}</InlineAlert> : null}
            <Button loading={busy} onClick={() => void save()}>
              {t('save')}
            </Button>
          </CardBody>
        </Card>
      </PageSection>
    </>
  );
}
