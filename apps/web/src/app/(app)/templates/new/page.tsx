'use client';

import { useRouter } from 'next/navigation';
import { useState } from 'react';
import { PageHeader } from '@/components/app/resource-view';
import { TemplateEditor, type TemplateDraft } from '@/components/app/template-editor';
import { useToast } from '@/components/ui/toast';
import { useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { toApiError } from '@/lib/api/errors';

/** The visual template editor: a template is written like a letter and saved without touching markup or JSON. */
export default function NewTemplatePage() {
  const t = useTranslations('templates');
  const tErrors = useTranslations('errors');
  const toast = useToast();
  const router = useRouter();
  const [busy, setBusy] = useState(false);

  const save = async (draft: TemplateDraft): Promise<void> => {
    setBusy(true);
    try {
      await api.templates.create(draft);
      toast.success(t('editor.saved'));
      router.push('/templates');
    } catch (error) {
      toast.error(tErrors(toApiError(error).kind));
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <PageHeader title={t('visual.title')} subtitle={t('visual.subtitle')} />
      <TemplateEditor busy={busy} onSave={(draft) => void save(draft)} />
    </>
  );
}
