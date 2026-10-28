'use client';

import { useEffect, useState } from 'react';
import { usePathname, useRouter } from 'next/navigation';
import { Button } from '@/components/ui/button';
import { useToast } from '@/components/ui/toast';
import { useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { toApiError } from '@/lib/api/errors';
import { useApiResource } from '@/lib/api/hooks';
import type { SessionUser } from '@/lib/api/types';
import { GuidedTour } from './guided-tour';

export const DEMO_EMAIL_DOMAIN = '@demo.invalid';

export function isDemoUser(user: SessionUser | null): boolean {
  return Boolean(user?.email.endsWith(DEMO_EMAIL_DOMAIN));
}

/**
 * The strip at the top of a demo cabinet: it says that the data is invented and will be thrown away, starts the guided tour (also by
 * itself on arrival) and ends the demo. Ending deletes everything the visitor did, so the cabinet is the same for the next visitor.
 */
export function DemoBanner({ user }: { readonly user: SessionUser | null }) {
  const t = useTranslations('demo');
  const toast = useToast();
  const router = useRouter();
  const pathname = usePathname();
  const demo = isDemoUser(user);
  const { state } = useApiResource((signal) => api.demo.status(signal), [demo], { skip: !demo });
  const [touring, setTouring] = useState(false);
  const [leaving, setLeaving] = useState(false);

  const persona = state.status === 'ready' ? state.data.persona : '';

  // Arriving from the demo button (?tour=1) starts the tour once.
  useEffect(() => {
    if (demo && persona && new URLSearchParams(window.location.search).get('tour') === '1') {
      setTouring(true);
      router.replace(pathname);
    }
  }, [demo, persona, pathname, router]);

  if (!demo) return null;

  const leave = async (): Promise<void> => {
    setLeaving(true);
    try {
      await api.demo.exit();
      window.location.assign('/');
    } catch (error) {
      toast.error(t('exitFailed'), toApiError(error).message);
      setLeaving(false);
    }
  };

  return (
    <>
      <div
        role="region"
        aria-label={t('banner.label')}
        style={{ display: 'flex', gap: '0.75rem', alignItems: 'center', flexWrap: 'wrap', padding: '0.5rem 1rem', background: 'var(--color-warning-bg, #fff4d6)', color: 'var(--color-warning-text, #5c4400)', borderBottom: '1px solid var(--color-border, #e0c97a)' }}
      >
        <strong>{t('banner.title')}</strong>
        <span style={{ flex: 1 }}>{t('banner.text')}</span>
        <Button size="sm" variant="secondary" onClick={() => setTouring(true)} disabled={!persona}>
          {t('banner.tour')}
        </Button>
        <Button size="sm" variant="primary" loading={leaving} onClick={() => void leave()}>
          {t('banner.exit')}
        </Button>
      </div>
      {touring ? <GuidedTour persona={persona} onClose={() => setTouring(false)} /> : null}
    </>
  );
}
