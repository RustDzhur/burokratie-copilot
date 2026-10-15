'use client';

import { useMemo, useState } from 'react';
import { PageSection } from '@/components/app/resource-view';
import { Card, CardBody } from '@/components/ui';
import { Button } from '@/components/ui/button';
import { Select } from '@/components/ui/form';
import { useToast } from '@/components/ui/toast';
import { useLocale, useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import { toApiError } from '@/lib/api/errors';
import { useApiResource } from '@/lib/api/hooks';

/** A wrong drawer can be corrected at any time: the person names the type, and re-sorting by the rules never undoes it. */
export function DocumentTypeChanger({
  documentId,
  currentName,
  onChanged,
}: {
  readonly documentId: string;
  readonly currentName: string | null;
  readonly onChanged: () => void;
}) {
  const t = useTranslations('documentViewer');
  const tDocs = useTranslations('documents');
  const tErrors = useTranslations('errors');
  const locale = useLocale();
  const toast = useToast();
  const catalogue = useApiResource((signal) => api.documents.catalogue(signal), []);
  const [code, setCode] = useState('');
  const [busy, setBusy] = useState(false);

  const options = useMemo(() => {
    if (catalogue.state.status !== 'ready') return [];
    return catalogue.state.data
      .map((entry) => ({
        value: entry.code,
        label: `${tDocs(`category.${entry.category}` as 'category.ALL')} · ${entry.names[locale] ?? entry.names.de}`,
      }))
      .sort((a, b) => a.label.localeCompare(b.label, locale));
  }, [catalogue.state, locale, tDocs]);

  const apply = async (): Promise<void> => {
    setBusy(true);
    try {
      await api.documents.setType(documentId, code);
      toast.success(t('type.changed'));
      setCode('');
      onChanged();
    } catch (error) {
      toast.error(tErrors(toApiError(error).kind));
    } finally {
      setBusy(false);
    }
  };

  return (
    <PageSection title={t('type.title')}>
      <Card>
        <CardBody>
          <p>{currentName ? t('type.current', { name: currentName }) : t('type.none')}</p>
          <Select label={t('type.choose')} placeholder={t('type.choose')} value={code} onChange={(event) => setCode(event.target.value)} options={options} />
          <Button loading={busy} disabled={code === ''} onClick={() => void apply()}>
            {t('type.apply')}
          </Button>
        </CardBody>
      </Card>
    </PageSection>
  );
}
