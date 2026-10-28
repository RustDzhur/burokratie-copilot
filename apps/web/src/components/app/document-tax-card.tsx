'use client';

import { useState } from 'react';
import { Card, CardBody, CardHeader } from '@/components/ui';
import { Button } from '@/components/ui/button';
import { InlineAlert } from '@/components/ui/feedback';
import { useLocale, useTranslations } from '@/i18n/provider';
import { api } from '@/lib/api';
import type { DocumentDetail } from '@/lib/api/types';

/**
 * The tax side of one document: which expense category it carries, the EÜR line and Kz it feeds,
 * where it is filed and why the rules chose it. A missing category is shown as missing, not hidden —
 * a receipt without a category is invisible to the report, which is the failure this card prevents.
 */
export function DocumentTaxCard({
  document,
  onChanged,
}: {
  readonly document: DocumentDetail;
  readonly onChanged?: () => void;
}) {
  const t = useTranslations('documentViewer');
  const locale = useLocale();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const category = document.expenseCategory ?? null;
  const names = category?.names ?? {};

  const runRules = async (): Promise<void> => {
    setBusy(true);
    setError(null);
    try {
      await api.documents.categorize(document.id);
      onChanged?.();
    } catch {
      setError(t('tax.error'));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Card>
      <CardHeader
        title={t('tax.title')}
        description={t('tax.description')}
        icon="file-text"
        headingLevel={3}
        actions={
          <Button variant="secondary" loading={busy} onClick={() => void runRules()} iconLeft="refresh">
            {t('tax.action')}
          </Button>
        }
      />
      <CardBody>
        {error ? <InlineAlert tone="danger">{error}</InlineAlert> : null}
        {category ? (
          <dl style={{ display: 'grid', gridTemplateColumns: 'max-content 1fr', gap: '0.4rem 1rem', margin: 0 }}>
            <dt>{t('tax.category')}</dt>
            <dd>
              <strong>{names[locale] ?? names.de ?? category.code}</strong>{' '}
              <code style={{ opacity: 0.7 }}>{category.code}</code>
            </dd>
            <dt>{t('tax.line')}</dt>
            <dd>{category.euerLine ? `${category.euerLine}${category.euerKz ? ` · Kz ${category.euerKz}` : ''}` : t('tax.notReported')}</dd>
            <dt>{t('tax.folder')}</dt>
            <dd>{document.folderPath ?? '—'}</dd>
            <dt>{t('tax.confidence')}</dt>
            <dd>
              {document.expenseCategoryConfidence != null
                ? `${Math.round(document.expenseCategoryConfidence * 100)} %`
                : '—'}
              {document.expenseCategorySource ? ` · ${document.expenseCategorySource}` : ''}
            </dd>
            {document.expenseCategoryRationale ? (
              <>
                <dt>{t('tax.rationale')}</dt>
                <dd>{document.expenseCategoryRationale}</dd>
              </>
            ) : null}
          </dl>
        ) : (
          <InlineAlert tone="warning">{t('tax.missing')}</InlineAlert>
        )}
      </CardBody>
    </Card>
  );
}
