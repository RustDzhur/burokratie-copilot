'use client';

import { useState } from 'react';
import { Button } from '@/components/ui/button';
import { Dialog } from '@/components/ui';
import { InlineAlert } from '@/components/ui/feedback';
import { RadioGroup } from '@/components/ui/form';
import { useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { toApiError } from '@/lib/api/errors';

const PERSONAS = ['FREELANCER', 'COMPANY', 'ADVISOR'] as const;

/**
 * "Try the demo": the visitor says what they would use the platform for and enters a demo cabinet of their own, without a password
 * or an account. The window asks one thing only, because the answer decides the cabinet, its plan and what is in it.
 */
export function DemoDialog({ open, onClose }: { readonly open: boolean; readonly onClose: () => void }) {
  const t = useTranslations('demo');
  const [persona, setPersona] = useState<string>('ADVISOR');
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);

  const start = async (): Promise<void> => {
    setBusy(true);
    setProblem(null);
    try {
      const result = await api.demo.start(persona);
      window.location.assign(result.next);
    } catch (error) {
      setProblem(toApiError(error).message || t('dialog.error'));
      setBusy(false);
    }
  };

  return (
    <Dialog
      open={open}
      onClose={onClose}
      title={t('dialog.title')}
      description={t('dialog.description')}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            {t('dialog.cancel')}
          </Button>
          <Button loading={busy} onClick={() => void start()}>
            {t('dialog.next')}
          </Button>
        </>
      }
    >
      <RadioGroup
        legend={t('dialog.legend')}
        name="demo-persona"
        value={persona}
        onChange={setPersona}
        options={PERSONAS.map((value) => ({
          value,
          label: t(`persona.${value}.title` as 'persona.ADVISOR.title'),
          description: t(`persona.${value}.description` as 'persona.ADVISOR.description'),
        }))}
      />
      {problem ? <InlineAlert tone="danger">{problem}</InlineAlert> : null}
      <p><small>{t('dialog.note')}</small></p>
    </Dialog>
  );
}
